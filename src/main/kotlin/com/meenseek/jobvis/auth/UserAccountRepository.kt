package com.meenseek.jobvis.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface UserAccountRepository : JpaRepository<UserAccount, UUID> {

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
