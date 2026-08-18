package com.meenseek.jobvis.connection

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID
import java.time.Instant

interface OAuthChallengeRepository : JpaRepository<OAuthChallenge, UUID> {
	@Query("SELECT challenge FROM OAuthChallenge challenge WHERE challenge.storedStateHash = :stateHash")
	fun findByStateHash(@Param("stateHash") stateHash: String): OAuthChallenge?

	@Query(
		"SELECT count(challenge) FROM OAuthChallenge challenge " +
			"WHERE challenge.storedUserId = :userId AND challenge.storedConsumedAt IS NULL " +
			"AND challenge.storedExpiresAt > :now",
	)
	fun countOutstandingForUser(@Param("userId") userId: UUID, @Param("now") now: Instant): Long

	@Query(
		"SELECT count(challenge) FROM OAuthChallenge challenge " +
			"WHERE challenge.storedUserId = :userId AND challenge.storedCreatedAt >= :createdAfter",
	)
	fun countCreatedForUserSince(
		@Param("userId") userId: UUID,
		@Param("createdAfter") createdAfter: Instant,
	): Long

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT challenge FROM OAuthChallenge challenge WHERE challenge.storedStateHash = :stateHash")
	fun findLockedByStateHash(@Param("stateHash") stateHash: String): OAuthChallenge?
}
