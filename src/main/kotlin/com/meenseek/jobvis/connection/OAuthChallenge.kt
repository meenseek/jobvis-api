package com.meenseek.jobvis.connection

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class OAuthFlowType { MAIL_CONNECTION, CALENDAR_CONNECTION }
enum class OAuthAuthority { GOOGLE, MICROSOFT }

@Entity
@Table(name = "oauth_challenges")
class OAuthChallenge private constructor(
	id: UUID,
	userId: UUID,
	flowType: OAuthFlowType,
	provider: OAuthAuthority,
	stateHash: String,
	encryptedPkceVerifier: String,
	redirectUri: String,
	expiresAt: Instant,
	exchangeClaimToken: UUID?,
	exchangeClaimExpiresAt: Instant?,
	consumedAt: Instant?,
	createdAt: Instant,
) {
	@field:Id
	@field:Column(name = "id", nullable = false)
	private var storedId: UUID = id

	@field:Column(name = "user_id", nullable = false)
	private var storedUserId: UUID = userId

	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "flow_type", nullable = false, length = 30)
	private var storedFlowType: OAuthFlowType = flowType

	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "provider", nullable = false, length = 30)
	private var storedProvider: OAuthAuthority = provider

	@field:Column(name = "state_hash", nullable = false, length = 64)
	private var storedStateHash: String = stateHash

	@field:Column(name = "encrypted_pkce_verifier", nullable = false, columnDefinition = "text")
	private var storedEncryptedPkceVerifier: String = encryptedPkceVerifier

	@field:Column(name = "redirect_uri", nullable = false, length = 1000)
	private var storedRedirectUri: String = redirectUri

	@field:Column(name = "expires_at", nullable = false)
	private var storedExpiresAt: Instant = expiresAt

	@field:Column(name = "exchange_claim_token")
	private var storedExchangeClaimToken: UUID? = exchangeClaimToken

	@field:Column(name = "exchange_claim_expires_at")
	private var storedExchangeClaimExpiresAt: Instant? = exchangeClaimExpiresAt

	@field:Column(name = "consumed_at")
	private var storedConsumedAt: Instant? = consumedAt

	@field:Column(name = "created_at", nullable = false)
	private var storedCreatedAt: Instant = createdAt

	val id: UUID get() = storedId
	val userId: UUID get() = storedUserId
	val flowType: OAuthFlowType get() = storedFlowType
	val authority: OAuthAuthority get() = storedProvider
	val encryptedPkceVerifier: String get() = storedEncryptedPkceVerifier
	val redirectUri: String get() = storedRedirectUri
	val expiresAt: Instant get() = storedExpiresAt
	val consumed: Boolean get() = storedConsumedAt != null

	fun claim(token: UUID, now: Instant, expiresAt: Instant): Boolean {
		if (storedConsumedAt != null) return false
		if (storedExchangeClaimToken != null && storedExchangeClaimExpiresAt?.isAfter(now) == true) return false
		storedExchangeClaimToken = token
		storedExchangeClaimExpiresAt = expiresAt
		return true
	}

	fun claimedBy(token: UUID): Boolean = storedExchangeClaimToken == token && storedConsumedAt == null

	fun release(token: UUID) {
		if (!claimedBy(token)) return
		storedExchangeClaimToken = null
		storedExchangeClaimExpiresAt = null
	}

	fun consume(token: UUID, now: Instant) {
		require(storedConsumedAt == null) { "이미 사용된 OAuth 요청입니다." }
		require(storedExchangeClaimToken == token) { "OAuth 교환 claim이 일치하지 않습니다." }
		storedExchangeClaimToken = null
		storedExchangeClaimExpiresAt = null
		storedConsumedAt = now
	}

	companion object {
		fun create(
			id: UUID,
			userId: UUID,
			flowType: OAuthFlowType,
			provider: OAuthAuthority,
			stateHash: String,
			encryptedPkceVerifier: String,
			redirectUri: String,
			now: Instant,
			expiresAt: Instant,
		): OAuthChallenge = OAuthChallenge(
			id, userId, flowType, provider, stateHash, encryptedPkceVerifier,
			redirectUri, expiresAt, null, null, null, now,
		)
	}
}
