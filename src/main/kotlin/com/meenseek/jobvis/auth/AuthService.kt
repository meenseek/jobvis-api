package com.meenseek.jobvis.auth

import com.meenseek.jobvis.common.NotFoundException
import com.meenseek.jobvis.common.ServiceUnavailableException
import com.meenseek.jobvis.common.UnauthorizedException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class AuthService(
	private val identityTokenVerifier: IdentityTokenVerifier,
	private val exchangeTransactionService: AuthExchangeTransactionService,
	private val sessionRepository: AuthSessionRepository,
	private val userAccountRepository: UserAccountRepository,
	private val loginChallengeRepository: LoginChallengeRepository,
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
	@Value("\${jobvis.auth.challenge-ttl:PT5M}")
	private val challengeTtl: Duration,
	@Value("\${jobvis.auth.max-outstanding-challenges:10000}")
	private val maxOutstandingChallenges: Long,
) {
	init {
		require(maxOutstandingChallenges in 1..1_000_000) {
			"jobvis.auth.max-outstanding-challenges는 1~1000000이어야 합니다."
		}
	}
	fun providers(): List<LoginProviderResponse> = LoginProvider.entries.map { provider ->
		LoginProviderResponse(provider.name.lowercase(), identityTokenVerifier.isConfigured(provider))
	}

	@Transactional
	fun createChallenge(request: CreateLoginChallengeRequest): LoginChallengeResponse {
		val provider = LoginProvider.fromApiValue(request.provider)
		if (!identityTokenVerifier.isConfigured(provider)) {
			throw ServiceUnavailableException("${provider.name.lowercase()} 로그인이 아직 설정되지 않았습니다.")
		}
		val challengeToken = SessionTokens.generate()
		val nonce = SessionTokens.generate()
		val now = Instant.now(clock)
		val expiresAt = now.plus(challengeTtl)
		jdbcTemplate.execute("SELECT pg_advisory_xact_lock(742019381)")
		if (loginChallengeRepository.countOutstanding(now) >= maxOutstandingChallenges) {
			throw com.meenseek.jobvis.common.TooManyRequestsException(
				"로그인 대기 요청이 많습니다. 잠시 후 다시 시도해 주세요.",
			)
		}
		loginChallengeRepository.save(
			LoginChallenge.create(
				UUID.randomUUID(), provider, TokenDigests.sha256Hex(challengeToken),
				TokenDigests.sha256Hex(nonce), now, expiresAt,
			),
		)
		return LoginChallengeResponse(challengeToken, nonce, expiresAt)
	}

	fun exchange(request: ExchangeIdentityTokenRequest): AuthSessionResponse {
		val provider = LoginProvider.fromApiValue(request.provider)
		val challengeHash = TokenDigests.sha256Hex(request.challengeToken)
		val nonceHash = TokenDigests.sha256Hex(request.nonce)
		val challenge = loginChallengeRepository.findByChallengeHash(challengeHash)
			?: throw UnauthorizedException("로그인 챌린지가 올바르지 않습니다.")
		val now = Instant.now(clock)
		if (challenge.provider != provider || challenge.consumed || !challenge.expiresAt.isAfter(now) ||
			challenge.nonceHash != nonceHash
		) {
			throw UnauthorizedException("로그인 챌린지가 만료되었거나 올바르지 않습니다.")
		}
		val verified = identityTokenVerifier.verify(provider, request.idToken, request.nonce)
		return exchangeTransactionService.exchangeVerified(
			provider,
			challengeHash,
			nonceHash,
			verified,
		)
	}

	@Transactional(readOnly = true)
	fun me(userId: UUID): AuthUserResponse = userAccountRepository.findById(userId)
		.orElseThrow { NotFoundException("사용자 정보를 찾을 수 없습니다.") }
		.toResponse()

	@Transactional
	fun logout(request: HttpServletRequest) {
		val tokenHash = TokenDigests.sha256Hex(BearerTokens.from(request))
		val session = sessionRepository.findLockedByTokenHash(tokenHash) ?: return
		session.revoke(Instant.now(clock))
	}

	private fun UserAccount.toResponse(): AuthUserResponse =
		AuthUserResponse(id, displayName, primaryEmail)
}

@Service
class AuthExchangeTransactionService(
	private val identityRepository: AuthIdentityRepository,
	private val sessionRepository: AuthSessionRepository,
	private val userAccountRepository: UserAccountRepository,
	private val loginChallengeRepository: LoginChallengeRepository,
	private val clock: Clock,
	@Value("\${jobvis.auth.session-ttl:PT168H}") private val sessionTtl: Duration,
) {
	@Transactional
	fun exchangeVerified(
		provider: LoginProvider,
		challengeHash: String,
		nonceHash: String,
		verified: VerifiedIdentity,
	): AuthSessionResponse {
		val challenge = loginChallengeRepository.findLockedByChallengeHash(challengeHash)
			?: throw UnauthorizedException("로그인 챌린지가 올바르지 않습니다.")
		val now = Instant.now(clock)
		if (challenge.provider != provider || challenge.consumed || !challenge.expiresAt.isAfter(now) ||
			challenge.nonceHash != nonceHash
		) {
			throw UnauthorizedException("로그인 챌린지가 만료되었거나 올바르지 않습니다.")
		}
		challenge.consume(now)
		loginChallengeRepository.saveAndFlush(challenge)
		val identity = identityRepository.findByProviderAndSubject(provider, verified.subject)
		val user = if (identity == null) {
			val createdUser = userAccountRepository.save(
				UserAccount.create(UUID.randomUUID(), verified.displayName, verified.email, now),
			)
			identityRepository.save(
				AuthIdentity.create(
					UUID.randomUUID(), createdUser.id, provider, verified.subject,
					verified.email, verified.emailVerified, now,
				),
			)
			createdUser
		} else {
			identity.recordLogin(verified.email, verified.emailVerified, now)
			val existingUser = userAccountRepository.findById(identity.userId)
				.orElseThrow { IllegalStateException("로그인 사용자 정보를 찾을 수 없습니다.") }
			existingUser.updateProfile(verified.displayName, verified.email, now)
			existingUser
		}

		val rawToken = uniqueSessionToken()
		val expiresAt = now.plus(sessionTtl)
		sessionRepository.save(
			AuthSession.create(UUID.randomUUID(), user.id, TokenDigests.sha256Hex(rawToken), now, expiresAt),
		)
		return AuthSessionResponse(
			rawToken,
			expiresAt = expiresAt,
			user = AuthUserResponse(user.id, user.displayName, user.primaryEmail),
		)
	}

	private fun uniqueSessionToken(): String = generateSequence(SessionTokens::generate)
		.take(3)
		.firstOrNull { candidate -> !sessionRepository.existsByTokenHash(TokenDigests.sha256Hex(candidate)) }
		?: throw IllegalStateException("세션 토큰을 생성할 수 없습니다.")
}
