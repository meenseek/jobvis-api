package com.meenseek.jobvis.imports

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import java.time.Instant
import java.util.UUID

interface ImportRunRepository : JpaRepository<ImportRun, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(
		"SELECT run FROM ImportRun run WHERE run.storedUserId = :userId " +
			"AND run.storedMutationId = :mutationId",
	)
	fun findByMutationLocked(
		@Param("userId") userId: UUID,
		@Param("mutationId") mutationId: UUID,
	): ImportRun?

	@Query(
		"SELECT run FROM ImportRun run WHERE run.storedUserId = :userId " +
			"ORDER BY run.storedCreatedAt DESC, run.storedId ASC",
	)
	fun findAllForUser(@Param("userId") userId: UUID, pageable: Pageable): Slice<ImportRun>

	@Query("SELECT run FROM ImportRun run WHERE run.storedId = :id AND run.storedUserId = :userId")
	fun findOwned(@Param("id") id: UUID, @Param("userId") userId: UUID): ImportRun?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(
		"SELECT run FROM ImportRun run WHERE run.storedId = :id " +
			"AND run.storedLeaseOwner = :leaseOwner",
	)
	fun findClaimedLocked(
		@Param("id") id: UUID,
		@Param("leaseOwner") leaseOwner: UUID,
	): ImportRun?

	@Query(
		"SELECT count(run) > 0 FROM ImportRun run WHERE run.storedUserId = :userId " +
			"AND run.storedConnectionId = :connectionId " +
			"AND run.storedStatus IN (com.meenseek.jobvis.imports.ImportRunStatus.QUEUED, " +
			"com.meenseek.jobvis.imports.ImportRunStatus.RUNNING)",
	)
	fun existsActive(
		@Param("userId") userId: UUID,
		@Param("connectionId") connectionId: UUID,
	): Boolean
}
