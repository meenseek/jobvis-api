package com.meenseek.jobvis.connection

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class ConnectionCapabilityResponse(
	val provider: String,
	val capability: String,
	val connectionMode: String,
	val available: Boolean,
	val supportsHistoricalImport: Boolean,
	val supportsOngoingSync: Boolean,
	val notes: List<String>,
)

data class ExternalConnectionResponse(
	val id: UUID,
	val provider: String,
	val capability: String,
	val accountEmail: String,
	val status: String,
	val ongoingSyncConsent: Boolean,
	val lastSyncedAt: Instant?,
	val nextSyncAfter: Instant?,
	val lastErrorCode: String?,
	val monitoringPaused: Boolean,
	val tokenExpiresAt: Instant?,
	val grantedScopes: Set<String>,
	val version: Long,
)

data class ConnectNaverRequest(
	@field:Email
	@field:NotBlank
	@field:Size(max = 320)
	val accountEmail: String,
	@field:NotBlank
	@field:Size(max = 255)
	val appPassword: String,
	val ongoingSyncConsent: Boolean = false,
)

data class UpdateMonitoringConsentRequest(
	@field:PositiveOrZero
	val expectedVersion: Long,
	val enabled: Boolean,
)

data class ResumeMonitoringRequest(
	@field:PositiveOrZero
	val expectedVersion: Long,
)

data class BeginOAuthConnectionRequest(
	@field:NotBlank
	@field:Size(max = 1000)
	val redirectUri: String,
)

data class BeginOAuthConnectionResponse(
	val provider: String,
	val authorizationUrl: String,
	val expiresAt: Instant,
)

data class CompleteOAuthConnectionRequest(
	@field:NotBlank
	@field:Size(max = 500)
	val state: String,
	@field:NotBlank
	@field:Size(max = 4000)
	val code: String,
	val ongoingSyncConsent: Boolean = false,
)
