package com.meenseek.jobvis.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

interface UserAccountRepository : JpaRepository<UserAccount, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT user FROM UserAccount user WHERE user.storedId = :userId")
	fun findLocked(@org.springframework.data.repository.query.Param("userId") userId: UUID): UserAccount?

	@Modifying
	@Query(
		value = """
			INSERT INTO users (id, created_at, updated_at)
			VALUES (:userId, :now, :now)
			ON CONFLICT (id) DO NOTHING
		""",
		nativeQuery = true,
	)
	fun provisionLocalUser(userId: UUID, now: Instant)
}
