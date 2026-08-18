package com.meenseek.jobvis.connection

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.UUID

interface ExternalConnectionRepository : JpaRepository<ExternalConnection, UUID> {
	@Query(
		"SELECT connection FROM ExternalConnection connection " +
			"WHERE connection.storedUserId = :userId " +
			"ORDER BY connection.storedCreatedAt DESC, connection.storedId ASC",
	)
	fun findAllForUser(@Param("userId") userId: UUID): List<ExternalConnection>

	@Query(
		"SELECT connection FROM ExternalConnection connection " +
			"WHERE connection.storedId = :id AND connection.storedUserId = :userId",
	)
	fun findOwned(@Param("id") id: UUID, @Param("userId") userId: UUID): ExternalConnection?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(
		"SELECT connection FROM ExternalConnection connection " +
			"WHERE connection.storedId = :id AND connection.storedUserId = :userId",
	)
	fun findOwnedLocked(@Param("id") id: UUID, @Param("userId") userId: UUID): ExternalConnection?

	@Query(
		"SELECT connection FROM ExternalConnection connection " +
			"WHERE connection.storedUserId = :userId " +
			"AND connection.storedProvider = :provider " +
			"AND lower(connection.storedAccountEmail) = lower(:accountEmail)",
	)
	fun findExisting(
		@Param("userId") userId: UUID,
		@Param("provider") provider: ConnectionProvider,
		@Param("accountEmail") accountEmail: String,
	): ExternalConnection?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(
		"SELECT connection FROM ExternalConnection connection " +
			"WHERE connection.storedUserId = :userId " +
			"AND connection.storedProvider = :provider " +
			"AND lower(connection.storedAccountEmail) = lower(:accountEmail)",
	)
	fun findExistingLocked(
		@Param("userId") userId: UUID,
		@Param("provider") provider: ConnectionProvider,
		@Param("accountEmail") accountEmail: String,
	): ExternalConnection?

	@Query(
		"SELECT connection FROM ExternalConnection connection " +
			"WHERE connection.storedStatus = com.meenseek.jobvis.connection.ConnectionStatus.CONNECTED " +
			"AND connection.storedOngoingSyncConsent = true " +
			"AND connection.storedNextSyncAfter IS NOT NULL " +
			"AND connection.storedNextSyncAfter <= :now " +
			"ORDER BY connection.storedNextSyncAfter, connection.storedId",
	)
	fun findDueForSync(@Param("now") now: Instant, pageable: Pageable): List<ExternalConnection>
}
