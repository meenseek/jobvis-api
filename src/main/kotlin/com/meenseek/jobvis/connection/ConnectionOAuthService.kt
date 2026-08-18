package com.meenseek.jobvis.connection

import com.meenseek.jobvis.auth.TokenDigests
import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ConflictException
import com.meenseek.jobvis.common.NotFoundException
import com.meenseek.jobvis.common.ServiceUnavailableException
import com.meenseek.jobvis.common.TooManyRequestsException
import com.meenseek.jobvis.security.CredentialCipher
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
class ConnectionOAuthService(
	private val challengeRepository: OAuthChallengeRepository,
	private val oauthConnectionClient: OAuthConnectionClient,
	private val connectionService: ConnectionService,
	private val credentialCipher: CredentialCipher,
	private val transactionTemplate: TransactionTemplate,
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
	@Value("\${jobvis.connections.allowed-redirect-uris:}") allowedRedirectUris: String,
	@Value("\${jobvis.connections.oauth-max-outstanding-per-user:10}")
	private val maxOutstandingPerUser: Long,
	@Value("\${jobvis.connections.oauth-start-rate-window:PT10M}")
	private val startRateWindow: Duration,
	@Value("\${jobvis.connections.oauth-start-rate-per-user:30}")
	private val maxStartsPerUser: Long,
	@Value("\${jobvis.connections.oauth-exchange-lease:PT2M}")
	private val exchangeLease: Duration,
	@Value("\${jobvis.external-http.connect-timeout:PT5S}") externalConnectTimeout: Duration,
	@Value("\${jobvis.external-http.read-timeout:PT30S}") externalReadTimeout: Duration,
) {
	private val secureRandom = SecureRandom()
	private val allowedRedirectUris = allowedRedirectUris.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

	init {
		require(maxOutstandingPerUser in 1..1000) { "OAuth 사용자별 대기 요청 상한은 1~1000이어야 합니다." }
		require(!startRateWindow.isZero && !startRateWindow.isNegative) { "OAuth 시작 rate window는 양수여야 합니다." }
		require(maxStartsPerUser in 1..10_000) { "OAuth 사용자별 시작 상한은 1~10000이어야 합니다." }
		val minimumLease = externalConnectTimeout.plus(externalReadTimeout).multipliedBy(2).plusSeconds(10)
		require(exchangeLease > minimumLease) {
			"OAuth 교환 lease는 외부 token·userinfo 호출의 최대 합계보다 10초 이상 길어야 합니다."
		}
	}

	fun begin(
		userId: UUID,
		providerValue: String,
		request: BeginOAuthConnectionRequest,
	): BeginOAuthConnectionResponse {
		val provider = oauthProvider(providerValue)
		if (!credentialCipher.available) {
			throw ServiceUnavailableException("자격증명 암호화 키가 설정되지 않았습니다.")
		}
		if (!oauthConnectionClient.isConfigured(provider)) {
			throw ServiceUnavailableException("${provider.apiValue()} OAuth가 아직 설정되지 않았습니다.")
		}
		val redirectUri = request.redirectUri.trim()
		if (redirectUri !in allowedRedirectUris) {
			throw BadRequestException("등록되지 않은 OAuth redirectUri입니다.")
		}
		val state = randomToken(32)
		val verifier = randomToken(48)
		val challengeId = UUID.randomUUID()
		val now = Instant.now(clock)
		val expiresAt = now.plus(CHALLENGE_TTL)
		val challenge = OAuthChallenge.create(
			id = challengeId,
			userId = userId,
			flowType = if (provider.capability == ConnectionCapability.MAIL) {
				OAuthFlowType.MAIL_CONNECTION
			} else {
				OAuthFlowType.CALENDAR_CONNECTION
			},
			provider = authority(provider),
			stateHash = TokenDigests.sha256Hex(state),
			encryptedPkceVerifier = credentialCipher.encrypt(verifier, pkceContext(challengeId)),
			redirectUri = redirectUri,
			now = now,
			expiresAt = expiresAt,
		)
		transactionTemplate.executeWithoutResult {
			jdbcTemplate.execute("SELECT pg_advisory_xact_lock(742019382)")
			if (challengeRepository.countOutstandingForUser(userId, now) >= maxOutstandingPerUser ||
				challengeRepository.countCreatedForUserSince(userId, now.minus(startRateWindow)) >= maxStartsPerUser
			) {
				throw TooManyRequestsException("OAuth 연결 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")
			}
			challengeRepository.saveAndFlush(challenge)
		}
		return BeginOAuthConnectionResponse(
			provider = provider.apiValue(),
			authorizationUrl = oauthConnectionClient.authorizationUrl(
				provider,
				redirectUri,
				state,
				pkceChallenge(verifier),
			),
			expiresAt = expiresAt,
		)
	}

	fun complete(
		userId: UUID,
		providerValue: String,
		request: CompleteOAuthConnectionRequest,
	): ExternalConnectionResponse {
		val provider = oauthProvider(providerValue)
		val stateHash = TokenDigests.sha256Hex(request.state)
		val claimToken = UUID.randomUUID()
		val claimed = claimExchange(userId, provider, stateHash, claimToken)
		val tokens = try {
			oauthConnectionClient.exchange(provider, claimed.redirectUri, request.code, claimed.verifier)
		} catch (exception: OAuthCodeConsumedException) {
			consumeClaim(userId, provider, stateHash, claimToken)
			throw ServiceUnavailableException(exception.message ?: "외부 OAuth 연결을 완료할 수 없습니다.")
		} catch (exception: BadRequestException) {
			consumeClaim(userId, provider, stateHash, claimToken)
			throw exception
		} catch (exception: ServiceUnavailableException) {
			releaseClaim(userId, provider, stateHash, claimToken)
			throw exception
		} catch (exception: Exception) {
			releaseClaim(userId, provider, stateHash, claimToken)
			throw exception
		}
		return try {
			transactionTemplate.execute {
				val challenge = claimedChallenge(userId, provider, stateHash, claimToken)
				val response = connectionService.upsertOAuth(userId, provider, tokens, request.ongoingSyncConsent)
				challenge.consume(claimToken, Instant.now(clock))
				challengeRepository.save(challenge)
				response
			}
		} catch (exception: Exception) {
			consumeClaim(userId, provider, stateHash, claimToken)
			throw exception
		}
	}

	private fun claimExchange(
		userId: UUID,
		provider: ConnectionProvider,
		stateHash: String,
		claimToken: UUID,
	): ClaimedOAuthExchange = transactionTemplate.execute {
		val now = Instant.now(clock)
		val challenge = validatePending(
			challengeRepository.findLockedByStateHash(stateHash), userId, provider, now,
		)
		if (!challenge.claim(claimToken, now, now.plus(exchangeLease))) {
			throw ConflictException("OAuth 연결 요청이 이미 처리 중입니다.")
		}
		challengeRepository.saveAndFlush(challenge)
		ClaimedOAuthExchange(
			challenge.redirectUri,
			credentialCipher.decrypt(challenge.encryptedPkceVerifier, pkceContext(challenge.id)),
		)
	}

	private fun claimedChallenge(
		userId: UUID,
		provider: ConnectionProvider,
		stateHash: String,
		claimToken: UUID,
	): OAuthChallenge {
		val challenge = challengeRepository.findLockedByStateHash(stateHash)
			?: throw NotFoundException("OAuth 연결 요청을 찾을 수 없습니다.")
		validateIdentity(challenge, userId, provider)
		if (!challenge.claimedBy(claimToken)) throw ConflictException("OAuth 연결 claim이 만료되거나 변경되었습니다.")
		return challenge
	}

	private fun releaseClaim(userId: UUID, provider: ConnectionProvider, stateHash: String, claimToken: UUID) {
		transactionTemplate.executeWithoutResult {
			val challenge = challengeRepository.findLockedByStateHash(stateHash) ?: return@executeWithoutResult
			if (!sameIdentity(challenge, userId, provider)) return@executeWithoutResult
			challenge.release(claimToken)
			challengeRepository.save(challenge)
		}
	}

	private fun consumeClaim(userId: UUID, provider: ConnectionProvider, stateHash: String, claimToken: UUID) {
		transactionTemplate.executeWithoutResult {
			val challenge = challengeRepository.findLockedByStateHash(stateHash) ?: return@executeWithoutResult
			if (!sameIdentity(challenge, userId, provider) || !challenge.claimedBy(claimToken)) {
				return@executeWithoutResult
			}
			challenge.consume(claimToken, Instant.now(clock))
			challengeRepository.save(challenge)
		}
	}

	private fun validatePending(
		challenge: OAuthChallenge?,
		userId: UUID,
		provider: ConnectionProvider,
		now: Instant,
	): OAuthChallenge {
		if (challenge == null) throw NotFoundException("OAuth 연결 요청을 찾을 수 없습니다.")
		validateIdentity(challenge, userId, provider)
		val expectedFlow = if (provider.capability == ConnectionCapability.MAIL) {
			OAuthFlowType.MAIL_CONNECTION
		} else {
			OAuthFlowType.CALENDAR_CONNECTION
		}
		if (challenge.flowType != expectedFlow || challenge.consumed || !challenge.expiresAt.isAfter(now)) {
			throw BadRequestException("OAuth 연결 요청이 만료되었거나 이미 사용되었습니다.")
		}
		return challenge
	}

	private fun validateIdentity(challenge: OAuthChallenge, userId: UUID, provider: ConnectionProvider) {
		if (!sameIdentity(challenge, userId, provider)) {
			throw NotFoundException("OAuth 연결 요청을 찾을 수 없습니다.")
		}
	}

	private fun sameIdentity(challenge: OAuthChallenge, userId: UUID, provider: ConnectionProvider): Boolean =
		challenge.userId == userId && challenge.authority == authority(provider)

	private fun oauthProvider(value: String): ConnectionProvider = ConnectionProvider.fromApiValue(value).also {
		if (it.credentialKind != CredentialKind.OAUTH2) {
			throw BadRequestException("해당 공급자는 OAuth 연결을 지원하지 않습니다.")
		}
	}

	private fun authority(provider: ConnectionProvider): OAuthAuthority = when (provider) {
		ConnectionProvider.GMAIL, ConnectionProvider.GOOGLE_CALENDAR -> OAuthAuthority.GOOGLE
		ConnectionProvider.OUTLOOK -> OAuthAuthority.MICROSOFT
		ConnectionProvider.NAVER -> throw BadRequestException("네이버 메일은 OAuth 연결을 지원하지 않습니다.")
	}

	private fun randomToken(size: Int): String = ByteArray(size).also(secureRandom::nextBytes)
		.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

	private fun pkceChallenge(verifier: String): String = MessageDigest.getInstance("SHA-256")
		.digest(verifier.toByteArray(StandardCharsets.US_ASCII))
		.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

	private fun pkceContext(challengeId: UUID): String = "oauth-challenge:$challengeId:pkce-verifier"

	private companion object {
		val CHALLENGE_TTL: Duration = Duration.ofMinutes(10)
	}

	private data class ClaimedOAuthExchange(val redirectUri: String, val verifier: String)
}
