package com.meenseek.jobvis.calendar

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface CalendarExportRepository : JpaRepository<CalendarExport, UUID> {
	@Modifying
	@Query(
		value = """
			INSERT INTO calendar_exports (
			    id, user_id, schedule_id, connection_id, schedule_version, preview_hash,
			    idempotency_key, title, starts_at, ends_at, timezone, location, description,
			    status, provider_event_id, last_error_code, confirmed_at,
			    claim_token, claim_expires_at, created_at, updated_at
			) VALUES (
			    :id, :userId, :scheduleId, :connectionId, :scheduleVersion, :previewHash,
			    :idempotencyKey, :title, :startsAt, :endsAt, :timezone, :location, :description,
			    'PREVIEWED', NULL, NULL, NULL, NULL, NULL, :now, :now
			)
			ON CONFLICT DO NOTHING
		""",
		nativeQuery = true,
	)
	fun reservePreview(
		@Param("id") id: UUID,
		@Param("userId") userId: UUID,
		@Param("scheduleId") scheduleId: UUID,
		@Param("connectionId") connectionId: UUID,
		@Param("scheduleVersion") scheduleVersion: Long,
		@Param("previewHash") previewHash: String,
		@Param("idempotencyKey") idempotencyKey: UUID,
		@Param("title") title: String,
		@Param("startsAt") startsAt: Instant,
		@Param("endsAt") endsAt: Instant,
		@Param("timezone") timezone: String,
		@Param("location") location: String,
		@Param("description") description: String,
		@Param("now") now: Instant,
	): Int

	@Query(
		"SELECT export FROM CalendarExport export WHERE export.storedId = :id " +
			"AND export.storedUserId = :userId",
	)
	fun findOwned(@Param("id") id: UUID, @Param("userId") userId: UUID): CalendarExport?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(
		"SELECT export FROM CalendarExport export WHERE export.storedId = :id " +
			"AND export.storedUserId = :userId",
	)
	fun findOwnedLocked(@Param("id") id: UUID, @Param("userId") userId: UUID): CalendarExport?

	@Query(
		"SELECT export FROM CalendarExport export WHERE export.storedUserId = :userId " +
			"AND export.storedIdempotencyKey = :idempotencyKey",
	)
	fun findByIdempotencyKey(
		@Param("userId") userId: UUID,
		@Param("idempotencyKey") idempotencyKey: UUID,
	): CalendarExport?

}
