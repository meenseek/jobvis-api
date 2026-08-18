package com.meenseek.jobvis.connection

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ConflictException
import com.meenseek.jobvis.common.NotFoundException
import com.meenseek.jobvis.common.ServiceUnavailableException
import com.meenseek.jobvis.security.CredentialCipher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Service
class ConnectionService(
	private val connectionRepository: ExternalConnectionRepository,
	private val naverCredentialValidator: NaverCredentialValidator,
	private val naverConnectionAttemptGuard: NaverConnectionAttemptGuard,
	private val credentialCipher: CredentialCipher,
	private val oauthConnectionClient: OAuthConnectionClient,
	private val jdbcTemplate: JdbcTemplate,
	private val transactionTemplate: TransactionTemplate,
	private val clock: Clock,
) {
	fun capabilities(): List<ConnectionCapabilityResponse> = ConnectionProvider.entries.map { provider ->
		val providerConfigured = provider == ConnectionProvider.NAVER || oauthConnectionClient.isConfigured(provider)
		ConnectionCapabilityResponse(
			provider = provider.apiValue(),
			capability = provider.capability.name.lowercase(Locale.ROOT),
			connectionMode = provider.credentialKind.name.lowercase(Locale.ROOT),
			available = providerConfigured && credentialCipher.available,
			supportsHistoricalImport = provider.capability == ConnectionCapability.MAIL,
			supportsOngoingSync = provider.capability == ConnectionCapability.MAIL,
			notes = notes(provider),
		)
	}

	@Transactional(readOnly = true)
	fun list(userId: UUID): List<ExternalConnectionResponse> =
		connectionRepository.findAllForUser(userId).map { connection -> connection.toResponse() }

	fun connectNaver(userId: UUID, request: ConnectNaverRequest): ExternalConnectionResponse {
		if (!credentialCipher.available) {
			throw ServiceUnavailableException("자격증명 암호화 키가 설정되지 않았습니다.")
		}
		val email = request.accountEmail.trim().lowercase(Locale.ROOT)
		val appPassword = request.appPassword.trim()
		naverConnectionAttemptGuard.execute(userId, email) {
			naverCredentialValidator.validate(email, appPassword)
		}
		return transactionTemplate.execute {
			val now = Instant.now(clock)
			val existing = connectionRepository.findExistingLocked(userId, ConnectionProvider.NAVER, email)
			val connectionId = existing?.id ?: UUID.randomUUID()
			if (existing != null) cancelActiveRuns(userId, connectionId, "CONNECTION_RECONNECTED", now)
			val encrypted = credentialCipher.encrypt(appPassword, appPasswordContext(connectionId))
			val connection = existing?.apply {
				reconnectAppPassword(encrypted, request.ongoingSyncConsent, now)
			} ?: ExternalConnection.createAppPassword(
				connectionId, userId, email, encrypted, request.ongoingSyncConsent, now,
			)
			connectionRepository.saveAndFlush(connection).toResponse()
		}
	}

	@Transactional
	fun updateMonitoringConsent(
		userId: UUID,
		connectionId: UUID,
		request: UpdateMonitoringConsentRequest,
	): ExternalConnectionResponse {
		val connection = connectionRepository.findOwnedLocked(connectionId, userId)
			?: throw NotFoundException("외부 연결을 찾을 수 없습니다.")
		if (connection.version != request.expectedVersion) {
			throw ConflictException("외부 연결이 다른 곳에서 변경되었습니다. 최신 내용을 확인해 주세요.")
		}
		if (connection.status == ConnectionStatus.REVOKED) {
			throw BadRequestException("해제된 연결의 자동 확인 동의는 변경할 수 없습니다.")
		}
		val now = Instant.now(clock)
		runCatching { connection.updateMonitoringConsent(request.enabled, now) }
			.getOrElse { throw BadRequestException(it.message ?: "자동 확인 동의를 변경할 수 없습니다.") }
		val response = connectionRepository.saveAndFlush(connection).toResponse()
		if (!request.enabled) {
			jdbcTemplate.update(
				"""
					UPDATE import_runs
					SET status = 'CANCELLED', error_code = 'MONITORING_CONSENT_REVOKED',
					    completed_at = ?, updated_at = ?
					WHERE user_id = ? AND connection_id = ?
					  AND requested_by = 'MONITOR' AND status = 'QUEUED'
				""".trimIndent(),
				Timestamp.from(now), Timestamp.from(now), userId, connectionId,
			)
		}
		return response
	}

	@Transactional
	fun resumeMonitoring(
		userId: UUID,
		connectionId: UUID,
		request: ResumeMonitoringRequest,
	): ExternalConnectionResponse {
		val connection = connectionRepository.findOwnedLocked(connectionId, userId)
			?: throw NotFoundException("외부 연결을 찾을 수 없습니다.")
		if (connection.version != request.expectedVersion) {
			throw ConflictException("외부 연결이 다른 곳에서 변경되었습니다. 최신 내용을 확인해 주세요.")
		}
		if (connection.status != ConnectionStatus.CONNECTED || !connection.ongoingSyncConsent ||
			connection.provider.capability != ConnectionCapability.MAIL
		) {
			throw BadRequestException("동의가 유지된 연결만 자동 확인을 재개할 수 있습니다.")
		}
		connection.updateMonitoringConsent(true, Instant.now(clock))
		return connectionRepository.saveAndFlush(connection).toResponse()
	}

	@Transactional
	fun upsertOAuth(
		userId: UUID,
		provider: ConnectionProvider,
		tokens: OAuthConnectionTokens,
		ongoingSyncConsent: Boolean,
	): ExternalConnectionResponse {
		if (!credentialCipher.available) {
			throw ServiceUnavailableException("자격증명 암호화 키가 설정되지 않았습니다.")
		}
		if (provider.credentialKind != CredentialKind.OAUTH2) {
			throw BadRequestException("OAuth 연결을 지원하지 않는 공급자입니다.")
		}
		if (!provider.hasRequiredScopes(tokens.scopes)) {
			throw BadRequestException("${provider.apiValue()} 연결에 필요한 권한이 승인되지 않았습니다.")
		}
		val now = Instant.now(clock)
		val email = tokens.accountEmail.trim().lowercase(Locale.ROOT)
		val existing = connectionRepository.findExistingLocked(userId, provider, email)
		val connectionId = existing?.id ?: UUID.randomUUID()
		if (existing != null) cancelActiveRuns(userId, connectionId, "CONNECTION_RECONNECTED", now)
		val encryptedAccessToken = credentialCipher.encrypt(
			tokens.accessToken,
			accessTokenContext(connectionId),
		)
		val encryptedRefreshToken = tokens.refreshToken?.let { refreshToken ->
			credentialCipher.encrypt(refreshToken, refreshTokenContext(connectionId))
		} ?: existing?.encryptedRefreshToken
		val connection = existing?.apply {
			reconnectOAuth(
				encryptedAccessToken,
				encryptedRefreshToken,
				tokens.expiresAt,
				tokens.scopes,
				ongoingSyncConsent,
				now,
			)
		} ?: ExternalConnection.createOAuth(
			connectionId,
			userId,
			provider,
			email,
			encryptedAccessToken,
			encryptedRefreshToken,
			tokens.expiresAt,
			tokens.scopes,
			ongoingSyncConsent,
			now,
		)
		return connectionRepository.saveAndFlush(connection).toResponse()
	}

	@Transactional
	fun revoke(userId: UUID, connectionId: UUID) {
		val connection = connectionRepository.findOwnedLocked(connectionId, userId)
			?: throw NotFoundException("외부 연결을 찾을 수 없습니다.")
		if (connection.status != ConnectionStatus.REVOKED) {
			val now = Instant.now(clock)
			cancelActiveRuns(userId, connectionId, "CONNECTION_REVOKED", now)
			connection.revoke(now)
			connectionRepository.saveAndFlush(connection)
		}
	}

	fun findOwned(userId: UUID, connectionId: UUID): ExternalConnection =
		connectionRepository.findOwned(connectionId, userId)
			?: throw NotFoundException("외부 연결을 찾을 수 없습니다.")

	private fun cancelActiveRuns(userId: UUID, connectionId: UUID, errorCode: String, now: Instant) {
		jdbcTemplate.update(
			"""
				UPDATE import_runs
				SET status = 'CANCELLED', error_code = ?, completed_at = ?, updated_at = ?,
				    lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL
				WHERE user_id = ? AND connection_id = ? AND status IN ('QUEUED', 'RUNNING')
			""".trimIndent(),
			errorCode, Timestamp.from(now), Timestamp.from(now), userId, connectionId,
		)
	}

	private fun notes(provider: ConnectionProvider): List<String> = when (provider) {
		ConnectionProvider.GMAIL -> listOf("Google OAuth의 gmail.readonly 동의가 필요합니다.")
		ConnectionProvider.OUTLOOK -> listOf("Microsoft OAuth의 Mail.Read 동의가 필요합니다.")
		ConnectionProvider.NAVER -> listOf("2단계 인증과 네이버 앱 비밀번호가 필요합니다.", "일반 계정 비밀번호는 받지 않습니다.")
		ConnectionProvider.GOOGLE_CALENDAR -> listOf("메일 동의와 별도로 Google Calendar 일정 쓰기 동의가 필요합니다.")
	}

	private fun ExternalConnection.toResponse(): ExternalConnectionResponse = ExternalConnectionResponse(
		id = id,
		provider = provider.apiValue(),
		capability = provider.capability.name.lowercase(Locale.ROOT),
		accountEmail = accountEmail,
		status = status.name.lowercase(Locale.ROOT),
		ongoingSyncConsent = ongoingSyncConsent,
		lastSyncedAt = lastSyncedAt,
		nextSyncAfter = nextSyncAfter,
		lastErrorCode = lastErrorCode,
		monitoringPaused = status == ConnectionStatus.CONNECTED && provider.capability == ConnectionCapability.MAIL &&
			ongoingSyncConsent && nextSyncAfter == null,
		tokenExpiresAt = tokenExpiresAt,
		grantedScopes = grantedScopes,
		version = version,
	)

	companion object {
		fun accessTokenContext(connectionId: UUID): String = "connection:$connectionId:access-token"
		fun refreshTokenContext(connectionId: UUID): String = "connection:$connectionId:refresh-token"
		fun appPasswordContext(connectionId: UUID): String = "connection:$connectionId:app-password"
	}
}
