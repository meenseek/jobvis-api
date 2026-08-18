package com.meenseek.jobvis.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "login_challenges")
class LoginChallenge private constructor(
	id: UUID,
	provider: LoginProvider,
	challengeHash: String,
	nonceHash: String,
	expiresAt: Instant,
	consumedAt: Instant?,
	createdAt: Instant,
) {
	@field:Id
	@field:Column(name = "id", nullable = false)
	private var storedId: UUID = id
	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "provider", nullable = false, length = 20)
	private var storedProvider: LoginProvider = provider
	@field:Column(name = "challenge_hash", nullable = false, length = 64)
	private var storedChallengeHash: String = challengeHash
	@field:Column(name = "nonce_hash", nullable = false, length = 64)
	private var storedNonceHash: String = nonceHash
	@field:Column(name = "expires_at", nullable = false)
	private var storedExpiresAt: Instant = expiresAt
	@field:Column(name = "consumed_at")
	private var storedConsumedAt: Instant? = consumedAt
	@field:Column(name = "created_at", nullable = false)
	private var storedCreatedAt: Instant = createdAt

	val provider: LoginProvider get() = storedProvider
	val nonceHash: String get() = storedNonceHash
	val expiresAt: Instant get() = storedExpiresAt
	val consumed: Boolean get() = storedConsumedAt != null

	fun consume(now: Instant) {
		check(storedConsumedAt == null) { "이미 사용된 로그인 챌린지입니다." }
		storedConsumedAt = now
	}

	companion object {
		fun create(
			id: UUID,
			provider: LoginProvider,
			challengeHash: String,
			nonceHash: String,
			now: Instant,
			expiresAt: Instant,
		): LoginChallenge = LoginChallenge(
			id, provider, challengeHash, nonceHash, expiresAt, null, now,
		)
	}
}
