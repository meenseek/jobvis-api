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
@Table(name = "auth_identities")
class AuthIdentity private constructor(
	id: UUID,
	userId: UUID,
	provider: LoginProvider,
	subject: String,
	email: String?,
	emailVerified: Boolean,
	createdAt: Instant,
	lastLoginAt: Instant,
) {
	@field:Id
	@field:Column(name = "id", nullable = false)
	private var storedId: UUID = id

	@field:Column(name = "user_id", nullable = false)
	private var storedUserId: UUID = userId

	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "provider", nullable = false, length = 20)
	private var storedProvider: LoginProvider = provider

	@field:Column(name = "subject", nullable = false, length = 255)
	private var storedSubject: String = subject

	@field:Column(name = "email", length = 320)
	private var storedEmail: String? = email

	@field:Column(name = "email_verified", nullable = false)
	private var storedEmailVerified: Boolean = emailVerified

	@field:Column(name = "created_at", nullable = false)
	private var storedCreatedAt: Instant = createdAt

	@field:Column(name = "last_login_at", nullable = false)
	private var storedLastLoginAt: Instant = lastLoginAt

	val userId: UUID get() = storedUserId

	fun recordLogin(email: String?, emailVerified: Boolean, now: Instant) {
		storedEmail = email?.takeIf(String::isNotBlank)
		storedEmailVerified = emailVerified
		storedLastLoginAt = now
	}

	companion object {
		fun create(
			id: UUID,
			userId: UUID,
			provider: LoginProvider,
			subject: String,
			email: String?,
			emailVerified: Boolean,
			now: Instant,
		): AuthIdentity = AuthIdentity(id, userId, provider, subject, email, emailVerified, now, now)
	}
}
