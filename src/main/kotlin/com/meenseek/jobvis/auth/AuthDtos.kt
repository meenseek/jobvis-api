package com.meenseek.jobvis.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class ExchangeIdentityTokenRequest(
	@field:NotBlank
	val provider: String,
	@field:NotBlank
	@field:Size(max = 16_000)
	val idToken: String,
	@field:NotBlank
	@field:Size(max = 255)
	val challengeToken: String,
	@field:NotBlank
	@field:Size(max = 255)
	val nonce: String,
)

data class CreateLoginChallengeRequest(
	@field:NotBlank
	val provider: String,
)

data class LoginChallengeResponse(
	val challengeToken: String,
	val nonce: String,
	val expiresAt: Instant,
)

data class AuthUserResponse(
	val id: UUID,
	val displayName: String?,
	val primaryEmail: String?,
)

data class AuthSessionResponse(
	val accessToken: String,
	val tokenType: String = "Bearer",
	val expiresAt: Instant,
	val user: AuthUserResponse,
)

data class LoginProviderResponse(
	val provider: String,
	val configured: Boolean,
)
