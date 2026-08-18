package com.meenseek.jobvis.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class UserAccount private constructor(
	@field:Id
	@field:Column(name = "id", nullable = false)
	private var storedId: UUID,
	@field:Column(name = "display_name", length = 120)
	private var storedDisplayName: String?,
	@field:Column(name = "primary_email", length = 320)
	private var storedPrimaryEmail: String?,
	@field:Version
	@field:Column(name = "version", nullable = false)
	private var storedVersion: Long,
	@field:Column(name = "created_at", nullable = false)
	private var storedCreatedAt: Instant,
	@field:Column(name = "updated_at", nullable = false)
	private var storedUpdatedAt: Instant,
) {
	val id: UUID get() = storedId
	val displayName: String? get() = storedDisplayName
	val primaryEmail: String? get() = storedPrimaryEmail

	fun updateProfile(displayName: String?, primaryEmail: String?, now: Instant) {
		storedDisplayName = displayName?.takeIf(String::isNotBlank)
		storedPrimaryEmail = primaryEmail?.takeIf(String::isNotBlank)
		storedUpdatedAt = now
	}

	companion object {
		fun create(
			id: UUID,
			displayName: String?,
			primaryEmail: String?,
			now: Instant,
		): UserAccount = UserAccount(
			storedId = id,
			storedDisplayName = displayName?.takeIf(String::isNotBlank),
			storedPrimaryEmail = primaryEmail?.takeIf(String::isNotBlank),
			storedVersion = 0,
			storedCreatedAt = now,
			storedUpdatedAt = now,
		)
	}
}
