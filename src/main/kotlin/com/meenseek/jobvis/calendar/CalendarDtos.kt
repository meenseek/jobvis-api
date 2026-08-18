package com.meenseek.jobvis.calendar

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import java.time.Instant
import java.util.UUID

data class CreateCalendarPreviewRequest(
	val scheduleId: UUID,
	val connectionId: UUID,
	val idempotencyKey: UUID,
	@field:PositiveOrZero
	val expectedScheduleVersion: Long,
)

data class ConfirmCalendarExportRequest(
	@field:NotBlank
	val previewHash: String,
)

data class CalendarExportResponse(
	val id: UUID,
	val scheduleId: UUID,
	val connectionId: UUID,
	val scheduleVersion: Long,
	val previewHash: String,
	val title: String,
	val startsAt: Instant,
	val endsAt: Instant,
	val timezone: String,
	val location: String,
	val description: String,
	val status: String,
	val providerEventId: String?,
	val errorCode: String?,
	val confirmedAt: Instant?,
	val createdAt: Instant,
)
