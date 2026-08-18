package com.meenseek.jobvis.connection

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ExternalConnectionAuthorizationException
import com.meenseek.jobvis.common.ServiceUnavailableException
import com.meenseek.jobvis.security.CredentialCipher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class AuthorizedCredential(val value: String, val connectionVersion: Long)

@Service
class ConnectionCredentialService(
	private val connectionService: ConnectionService,
	private val connectionStateService: ConnectionStateService,
	private val refreshClaimService: OAuthRefreshClaimService,
	private val oauthConnectionClient: OAuthConnectionClient,
	private val credentialCipher: CredentialCipher,
	private val clock: Clock,
) {
	fun accessToken(userId: UUID, connectionId: UUID): String = authorizedAccessToken(userId, connectionId).value

	fun authorizedAccessToken(userId: UUID, connectionId: UUID): AuthorizedCredential {
		val connection = connectionService.findOwned(userId, connectionId)
		verifyConnected(connection)
		if (connection.provider.credentialKind != CredentialKind.OAUTH2) {
			throw BadRequestException("OAuth 액세스 토큰 연결이 아닙니다.")
		}
		verifyRequiredScopes(connection)
		val now = Instant.now(clock)
		val encryptedAccess = connection.encryptedAccessToken
		if (encryptedAccess != null && connection.tokenExpiresAt?.isAfter(now.plusSeconds(120)) != false) {
			return AuthorizedCredential(
				credentialCipher.decrypt(encryptedAccess, ConnectionService.accessTokenContext(connection.id)),
				connection.version,
			)
		}
		val encryptedRefresh = connection.encryptedRefreshToken
			?: throw ExternalConnectionAuthorizationException("외부 연결을 다시 승인해 주세요.", connection.version)
		val refreshToken = credentialCipher.decrypt(
			encryptedRefresh,
			ConnectionService.refreshTokenContext(connection.id),
		)
		val expectedVersion = connection.version
		val claimToken = refreshClaimService.tryClaim(userId, connectionId, expectedVersion, now)
			?: run {
				val latest = connectionService.findOwned(userId, connectionId)
				if (latest.version != expectedVersion || latest.status != ConnectionStatus.CONNECTED) {
					return authorizedAccessToken(userId, connectionId)
				}
				throw ServiceUnavailableException("외부 서비스 토큰을 갱신하고 있습니다. 잠시 후 다시 시도해 주세요.")
			}
		try {
			val refreshed = try {
				oauthConnectionClient.refresh(connection.provider, refreshToken)
			} catch (exception: OAuthRefreshException) {
				if (exception.disposition == OAuthRefreshFailureDisposition.REAUTHORIZATION_REQUIRED) {
					val marked = connectionStateService.markReauthorizationRequired(
						userId, connectionId, "OAUTH_REFRESH_FAILED", Instant.now(clock), expectedVersion, claimToken,
					)
					if (!marked) return authorizedAccessToken(userId, connectionId)
					throw ExternalConnectionAuthorizationException("외부 연결을 다시 승인해 주세요.", expectedVersion)
				}
				throw ServiceUnavailableException("외부 서비스 토큰을 일시적으로 갱신할 수 없습니다.")
			} catch (_: Exception) {
				throw ServiceUnavailableException("외부 서비스 토큰을 일시적으로 갱신할 수 없습니다.")
			}
			val nextAccess = credentialCipher.encrypt(
				refreshed.accessToken,
				ConnectionService.accessTokenContext(connection.id),
			)
			val nextRefresh = refreshed.refreshToken?.let { token ->
				credentialCipher.encrypt(token, ConnectionService.refreshTokenContext(connection.id))
			}
			val nextScopes = refreshed.scopes.ifEmpty { connection.grantedScopes }
			if (!connection.provider.hasRequiredScopes(nextScopes)) {
				val marked = connectionStateService.markReauthorizationRequired(
					userId, connectionId, "OAUTH_SCOPE_MISSING", Instant.now(clock), expectedVersion, claimToken,
				)
				if (!marked) return authorizedAccessToken(userId, connectionId)
				throw ExternalConnectionAuthorizationException(
					"외부 연결에 필요한 권한을 다시 승인해 주세요.", expectedVersion,
				)
			}
			val storedVersion = connectionStateService.storeRefreshedTokens(
				userId,
				connectionId,
				nextAccess,
				nextRefresh,
				refreshed.expiresAt,
				nextScopes,
				Instant.now(clock),
				expectedVersion,
				claimToken,
			)
			if (storedVersion == null) return authorizedAccessToken(userId, connectionId)
			return AuthorizedCredential(refreshed.accessToken, storedVersion)
		} finally {
			refreshClaimService.release(connectionId, claimToken)
		}
	}

	@Transactional(readOnly = true)
	fun appPassword(userId: UUID, connectionId: UUID): String = authorizedAppPassword(userId, connectionId).value

	@Transactional(readOnly = true)
	fun authorizedAppPassword(userId: UUID, connectionId: UUID): AuthorizedCredential {
		val connection = connectionService.findOwned(userId, connectionId)
		verifyConnected(connection)
		val encrypted = connection.encryptedAppPassword
			?: throw ExternalConnectionAuthorizationException("네이버 연결을 다시 설정해 주세요.", connection.version)
		return AuthorizedCredential(
			credentialCipher.decrypt(encrypted, ConnectionService.appPasswordContext(connection.id)), connection.version,
		)
	}

	private fun verifyConnected(connection: ExternalConnection) {
		if (connection.status != ConnectionStatus.CONNECTED) {
			throw ExternalConnectionAuthorizationException("외부 연결을 다시 승인해 주세요.", connection.version)
		}
	}

	private fun verifyRequiredScopes(connection: ExternalConnection) {
		if (!connection.provider.hasRequiredScopes(connection.grantedScopes)) {
			connectionStateService.markReauthorizationRequired(
				connection.userId, connection.id, "OAUTH_SCOPE_MISSING", Instant.now(clock), connection.version,
			)
			throw ExternalConnectionAuthorizationException(
				"외부 연결에 필요한 권한을 다시 승인해 주세요.", connection.version,
			)
		}
	}
}

@Service
class ConnectionStateService(
	private val connectionRepository: ExternalConnectionRepository,
	private val refreshClaimService: OAuthRefreshClaimService,
) {
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun markReauthorizationRequired(
		userId: UUID,
		connectionId: UUID,
		errorCode: String,
		now: Instant,
		expectedVersion: Long? = null,
		refreshClaimToken: UUID? = null,
	): Boolean {
		val connection = connectionRepository.findOwnedLocked(connectionId, userId) ?: return false
		if (connection.status == ConnectionStatus.REVOKED ||
			(expectedVersion != null && connection.version != expectedVersion) ||
			(refreshClaimToken != null && (expectedVersion == null ||
				!refreshClaimService.consumeOwned(connectionId, expectedVersion, refreshClaimToken, now)))
		) return false
		connection.markError(errorCode, reauthorizationRequired = true, now)
		connectionRepository.saveAndFlush(connection)
		return true
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun storeRefreshedTokens(
		userId: UUID,
		connectionId: UUID,
		encryptedAccessToken: String,
		encryptedRefreshToken: String?,
		expiresAt: Instant?,
		scopes: Set<String>,
		now: Instant,
		expectedVersion: Long,
		refreshClaimToken: UUID,
	): Long? {
		val connection = connectionRepository.findOwnedLocked(connectionId, userId) ?: return null
		if (connection.status != ConnectionStatus.CONNECTED || connection.version != expectedVersion ||
			!refreshClaimService.consumeOwned(connectionId, expectedVersion, refreshClaimToken, now)
		) return null
		connection.refreshOAuthTokens(
			encryptedAccessToken, encryptedRefreshToken, expiresAt, scopes, now,
		)
		connectionRepository.saveAndFlush(connection)
		return connection.version
	}
}
