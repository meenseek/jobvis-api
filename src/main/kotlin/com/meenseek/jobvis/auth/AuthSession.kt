package com.meenseek.jobvis.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "auth_sessions")
class AuthSession private constructor(
	id: UUID,
	userId: UUID,
	tokenHash: String,
	expiresAt: Instant,
	revokedAt: Instant?,
	createdAt: Instant,
	lastSeenAt: Instant,
) {
	@field:Id
	@field:Column(name = "id", nullable = false)
	private var storedId: UUID = id

	@field:Column(name = "user_id", nullable = false)
	private var storedUserId: UUID = userId

	@field:Column(name = "token_hash", nullable = false, length = 64)
	private var storedTokenHash: String = tokenHash

	@field:Column(name = "expires_at", nullable = false)
	private var storedExpiresAt: Instant = expiresAt

	@field:Column(name = "revoked_at")
	private var storedRevokedAt: Instant? = revokedAt

	@field:Column(name = "created_at", nullable = false)
	private var storedCreatedAt: Instant = createdAt

	@field:Column(name = "last_seen_at", nullable = false)
	private var storedLastSeenAt: Instant = lastSeenAt

	val userId: UUID get() = storedUserId
	val expiresAt: Instant get() = storedExpiresAt

	fun revoke(now: Instant) {
		if (storedRevokedAt == null) storedRevokedAt = now
	}

	companion object {
		fun create(id: UUID, userId: UUID, tokenHash: String, now: Instant, expiresAt: Instant): AuthSession =
			AuthSession(id, userId, tokenHash, expiresAt, null, now, now)
	}
}
