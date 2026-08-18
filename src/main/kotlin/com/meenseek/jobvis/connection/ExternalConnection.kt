package com.meenseek.jobvis.connection

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "external_connections")
class ExternalConnection private constructor(
	id: UUID,
	userId: UUID,
	provider: ConnectionProvider,
	accountEmail: String,
	credentialKind: CredentialKind,
	encryptedAccessToken: String?,
	encryptedRefreshToken: String?,
	encryptedAppPassword: String?,
	tokenExpiresAt: Instant?,
	grantedScopes: String,
	status: ConnectionStatus,
	ongoingSyncConsent: Boolean,
	consentedAt: Instant,
	lastSyncedAt: Instant?,
	nextSyncAfter: Instant?,
	lastErrorCode: String?,
	version: Long,
	createdAt: Instant,
	updatedAt: Instant,
	revokedAt: Instant?,
) {
	@field:Id
	@field:Column(name = "id", nullable = false)
	private var storedId: UUID = id

	@field:Column(name = "user_id", nullable = false)
	private var storedUserId: UUID = userId

	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "provider", nullable = false, length = 30)
	private var storedProvider: ConnectionProvider = provider

	@field:Column(name = "account_email", nullable = false, length = 320)
	private var storedAccountEmail: String = accountEmail

	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "credential_kind", nullable = false, length = 20)
	private var storedCredentialKind: CredentialKind = credentialKind

	@field:Column(name = "encrypted_access_token", columnDefinition = "text")
	private var storedEncryptedAccessToken: String? = encryptedAccessToken

	@field:Column(name = "encrypted_refresh_token", columnDefinition = "text")
	private var storedEncryptedRefreshToken: String? = encryptedRefreshToken

	@field:Column(name = "encrypted_app_password", columnDefinition = "text")
	private var storedEncryptedAppPassword: String? = encryptedAppPassword

	@field:Column(name = "token_expires_at")
	private var storedTokenExpiresAt: Instant? = tokenExpiresAt

	@field:Column(name = "granted_scopes", nullable = false, columnDefinition = "text")
	private var storedGrantedScopes: String = grantedScopes

	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "status", nullable = false, length = 32)
	private var storedStatus: ConnectionStatus = status

	@field:Column(name = "ongoing_sync_consent", nullable = false)
	private var storedOngoingSyncConsent: Boolean = ongoingSyncConsent

	@field:Column(name = "consented_at", nullable = false)
	private var storedConsentedAt: Instant = consentedAt

	@field:Column(name = "last_synced_at")
	private var storedLastSyncedAt: Instant? = lastSyncedAt

	@field:Column(name = "next_sync_after")
	private var storedNextSyncAfter: Instant? = nextSyncAfter

	@field:Column(name = "last_error_code", length = 80)
	private var storedLastErrorCode: String? = lastErrorCode

	@field:Version
	@field:Column(name = "version", nullable = false)
	private var storedVersion: Long = version

	@field:Column(name = "created_at", nullable = false)
	private var storedCreatedAt: Instant = createdAt

	@field:Column(name = "updated_at", nullable = false)
	private var storedUpdatedAt: Instant = updatedAt

	@field:Column(name = "revoked_at")
	private var storedRevokedAt: Instant? = revokedAt

	val id: UUID get() = storedId
	val userId: UUID get() = storedUserId
	val provider: ConnectionProvider get() = storedProvider
	val accountEmail: String get() = storedAccountEmail
	val status: ConnectionStatus get() = storedStatus
	val ongoingSyncConsent: Boolean get() = storedOngoingSyncConsent
	val lastSyncedAt: Instant? get() = storedLastSyncedAt
	val nextSyncAfter: Instant? get() = storedNextSyncAfter
	val lastErrorCode: String? get() = storedLastErrorCode
	val tokenExpiresAt: Instant? get() = storedTokenExpiresAt
	val version: Long get() = storedVersion
	val encryptedAccessToken: String? get() = storedEncryptedAccessToken
	val encryptedRefreshToken: String? get() = storedEncryptedRefreshToken
	val encryptedAppPassword: String? get() = storedEncryptedAppPassword
	val grantedScopes: Set<String> get() = storedGrantedScopes.split(' ').filter(String::isNotBlank).toSet()

	fun reconnectOAuth(
		encryptedAccessToken: String?,
		encryptedRefreshToken: String?,
		tokenExpiresAt: Instant?,
		grantedScopes: Set<String>,
		ongoingSyncConsent: Boolean,
		now: Instant,
	) {
		storedCredentialKind = CredentialKind.OAUTH2
		storedEncryptedAccessToken = encryptedAccessToken
		storedEncryptedRefreshToken = encryptedRefreshToken
		storedEncryptedAppPassword = null
		storedTokenExpiresAt = tokenExpiresAt
		storedGrantedScopes = grantedScopes.sorted().joinToString(" ")
		storedStatus = ConnectionStatus.CONNECTED
		storedOngoingSyncConsent = provider.capability == ConnectionCapability.MAIL && ongoingSyncConsent
		storedNextSyncAfter = if (storedOngoingSyncConsent) now else null
		storedConsentedAt = now
		storedLastErrorCode = null
		storedUpdatedAt = now
		storedRevokedAt = null
	}

	fun reconnectAppPassword(encryptedAppPassword: String, ongoingSyncConsent: Boolean, now: Instant) {
		storedCredentialKind = CredentialKind.APP_PASSWORD
		storedEncryptedAccessToken = null
		storedEncryptedRefreshToken = null
		storedEncryptedAppPassword = encryptedAppPassword
		storedTokenExpiresAt = null
		storedGrantedScopes = "imap.readonly"
		storedStatus = ConnectionStatus.CONNECTED
		storedOngoingSyncConsent = ongoingSyncConsent
		storedNextSyncAfter = if (storedOngoingSyncConsent) now else null
		storedConsentedAt = now
		storedLastErrorCode = null
		storedUpdatedAt = now
		storedRevokedAt = null
	}

	fun updateMonitoringConsent(enabled: Boolean, now: Instant) {
		require(provider.capability == ConnectionCapability.MAIL) { "메일 연결만 자동 확인 동의를 변경할 수 있습니다." }
		storedOngoingSyncConsent = enabled
		storedNextSyncAfter = if (enabled) now else null
		storedUpdatedAt = now
	}

	fun refreshOAuthTokens(
		encryptedAccessToken: String,
		encryptedRefreshToken: String?,
		tokenExpiresAt: Instant?,
		grantedScopes: Set<String>,
		now: Instant,
	) {
		require(storedCredentialKind == CredentialKind.OAUTH2) { "OAuth 연결만 토큰을 갱신할 수 있습니다." }
		storedEncryptedAccessToken = encryptedAccessToken
		if (encryptedRefreshToken != null) storedEncryptedRefreshToken = encryptedRefreshToken
		storedTokenExpiresAt = tokenExpiresAt
		storedGrantedScopes = grantedScopes.sorted().joinToString(" ")
		storedStatus = ConnectionStatus.CONNECTED
		storedLastErrorCode = null
		storedUpdatedAt = now
	}

	fun revoke(now: Instant) {
		storedEncryptedAccessToken = null
		storedEncryptedRefreshToken = null
		storedEncryptedAppPassword = null
		storedTokenExpiresAt = null
		storedOngoingSyncConsent = false
		storedNextSyncAfter = null
		storedStatus = ConnectionStatus.REVOKED
		storedUpdatedAt = now
		storedRevokedAt = now
	}

	fun markSynced(checkpointAt: Instant, nextSyncAfter: Instant?, now: Instant) {
		storedLastSyncedAt = storedLastSyncedAt?.let { previous -> maxOf(previous, checkpointAt) } ?: checkpointAt
		storedNextSyncAfter = if (storedOngoingSyncConsent) nextSyncAfter else null
		storedLastErrorCode = null
		storedUpdatedAt = now
	}

	fun pauseMonitoringAfterRunError(errorCode: String, now: Instant) {
		if (storedStatus != ConnectionStatus.CONNECTED) return
		storedLastErrorCode = errorCode.take(80)
		storedNextSyncAfter = null
		storedUpdatedAt = now
	}

	fun markError(errorCode: String, reauthorizationRequired: Boolean, now: Instant) {
		storedStatus = if (reauthorizationRequired) {
			ConnectionStatus.REAUTHORIZATION_REQUIRED
		} else {
			ConnectionStatus.ERROR
		}
		storedLastErrorCode = errorCode.take(80)
		storedNextSyncAfter = null
		storedUpdatedAt = now
	}

	fun markTransientError(errorCode: String, retryAt: Instant, now: Instant) {
		if (storedStatus != ConnectionStatus.CONNECTED) return
		storedLastErrorCode = errorCode.take(80)
		storedNextSyncAfter = if (storedOngoingSyncConsent) retryAt else null
		storedUpdatedAt = now
	}

	companion object {
		fun createOAuth(
			id: UUID,
			userId: UUID,
			provider: ConnectionProvider,
			accountEmail: String,
			encryptedAccessToken: String?,
			encryptedRefreshToken: String?,
			tokenExpiresAt: Instant?,
			grantedScopes: Set<String>,
			ongoingSyncConsent: Boolean,
			now: Instant,
		): ExternalConnection = ExternalConnection(
			id, userId, provider, accountEmail, CredentialKind.OAUTH2,
			encryptedAccessToken, encryptedRefreshToken, null, tokenExpiresAt,
			grantedScopes.sorted().joinToString(" "), ConnectionStatus.CONNECTED,
			provider.capability == ConnectionCapability.MAIL && ongoingSyncConsent,
			now, null, if (provider.capability == ConnectionCapability.MAIL && ongoingSyncConsent) now else null,
			null, 0, now, now, null,
		)

		fun createAppPassword(
			id: UUID,
			userId: UUID,
			accountEmail: String,
			encryptedAppPassword: String,
			ongoingSyncConsent: Boolean,
			now: Instant,
		): ExternalConnection = ExternalConnection(
			id, userId, ConnectionProvider.NAVER, accountEmail, CredentialKind.APP_PASSWORD,
			null, null, encryptedAppPassword, null, "imap.readonly", ConnectionStatus.CONNECTED,
			ongoingSyncConsent, now, null, if (ongoingSyncConsent) now else null,
			null, 0, now, now, null,
		)
	}
}
