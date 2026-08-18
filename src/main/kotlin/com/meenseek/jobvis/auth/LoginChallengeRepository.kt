package com.meenseek.jobvis.auth

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID
import java.time.Instant

interface LoginChallengeRepository : JpaRepository<LoginChallenge, UUID> {
	@Query("SELECT challenge FROM LoginChallenge challenge WHERE challenge.storedChallengeHash = :challengeHash")
	fun findByChallengeHash(@Param("challengeHash") challengeHash: String): LoginChallenge?

	@Query(
		"SELECT count(challenge) FROM LoginChallenge challenge " +
			"WHERE challenge.storedConsumedAt IS NULL AND challenge.storedExpiresAt > :now",
	)
	fun countOutstanding(@Param("now") now: Instant): Long

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT challenge FROM LoginChallenge challenge WHERE challenge.storedChallengeHash = :challengeHash")
	fun findLockedByChallengeHash(@Param("challengeHash") challengeHash: String): LoginChallenge?
}
