package com.meenseek.jobvis.application

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateRequest(
	val mutationId: UUID,
	@field:NotBlank
	@field:Size(max = 160)
	val company: String,
	@field:NotBlank
	@field:Size(max = 160)
	val position: String,
	@field:NotBlank
	val stage: String,
)

data class UpdateDetailsRequest(
	val mutationId: UUID,
	@field:PositiveOrZero
	val expectedVersion: Long,
	@field:NotBlank
	@field:Size(max = 160)
	val company: String,
	@field:NotBlank
	@field:Size(max = 160)
	val position: String,
	@field:Size(max = 160)
	val location: String?,
	@field:Size(max = 80)
	val employmentType: String?,
)

data class UpdateMemoRequest(
	val mutationId: UUID,
	@field:PositiveOrZero
	val expectedVersion: Long,
	@field:Size(max = 20_000)
	val memo: String,
)

data class UpdateStatusRequest(
	val mutationId: UUID,
	@field:PositiveOrZero
	val expectedVersion: Long,
	@field:NotBlank
	val status: String,
)

data class MutationRequest(
	val mutationId: UUID,
	@field:PositiveOrZero
	val expectedVersion: Long,
)

data class UpdateScheduleRequest(
	val mutationId: UUID,
	@field:PositiveOrZero
	val expectedVersion: Long,
	@field:PositiveOrZero
	val expectedScheduleVersion: Long,
	@field:NotBlank
	val scheduleType: String,
	@field:NotBlank
	@field:Size(max = 200)
	val action: String,
	val scheduledAt: Instant,
	val endsAt: Instant?,
	@field:NotBlank
	@field:Size(max = 80)
	val timezone: String,
	@field:Size(max = 300)
	val location: String,
	@field:Size(max = 4000)
	val description: String,
)

data class ApplicationScheduleResponse(
	val id: UUID,
	val applicationId: UUID,
	val applicationVersion: Long,
	val version: Long,
	val scheduleType: String,
	val action: String,
	val scheduledAt: Instant?,
	val endsAt: Instant?,
	val timezone: String,
	val location: String,
	val description: String,
	val completed: Boolean,
	val completedAt: Instant?,
)

data class ApplicationResponse(
	val id: UUID,
	val version: Long,
	val company: String,
	val position: String,
	val location: String,
	val employmentType: String,
	val appliedAt: LocalDate,
	val stage: String,
	val highestStageReached: String,
	val screeningPassed: Boolean,
	val result: String,
	val needsReview: Boolean,
	val source: String,
	val nextAction: String,
	val scheduleType: String,
	val nextActionAt: LocalDate?,
	val nextActionCompleted: Boolean,
	val memo: String,
	val emails: List<EmailResponse>,
	val activities: List<ActivityResponse>,
	val changes: List<ChangeResponse>,
)

data class ApplicationListItemResponse(
	val id: UUID,
	val version: Long,
	val company: String,
	val position: String,
	val location: String,
	val employmentType: String,
	val appliedAt: LocalDate,
	val stage: String,
	val highestStageReached: String,
	val screeningPassed: Boolean,
	val result: String,
	val needsReview: Boolean,
	val source: String,
	val nextAction: String,
	val scheduleType: String,
	val nextActionAt: LocalDate?,
	val nextActionCompleted: Boolean,
)

data class ApplicationListPageResponse(
	val items: List<ApplicationListItemResponse>,
	val page: Int,
	val limit: Int,
	val hasNext: Boolean,
)

data class HistoryPageResponse<T>(
	val items: List<T>,
	val nextCursor: Long?,
)

data class EmailResponse(
	val id: UUID,
	val subject: String,
	val sender: String,
	val receivedAt: Instant,
	val summary: String,
)

data class ActivityResponse(
	val id: UUID,
	val type: String,
	val title: String,
	val description: String,
	val occurredAt: Instant,
)

data class ChangeResponse(
	val id: UUID,
	val title: String,
	val description: String,
	val occurredAt: Instant,
)

internal fun ApplicationResponse.compact(): ApplicationResponse = copy(
	emails = emptyList(),
	activities = emptyList(),
	changes = emptyList(),
)
