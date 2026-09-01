package com.meenseek.jobvis.application

import com.fasterxml.jackson.annotation.JsonInclude
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
	val status: String,
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
	val appliedAt: LocalDate,
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

data class PatchScheduleRequest(
	val mutationId: UUID,
	val expectedVersion: Long,
	val nextActionAtPresent: Boolean,
	val nextActionAt: LocalDate?,
	val nextActionTitlePresent: Boolean,
	val nextActionTitle: String?,
)

data class UpdateScheduleRequest(
	val mutationId: UUID,
	@field:PositiveOrZero
	val expectedVersion: Long,
	@field:PositiveOrZero
	val expectedScheduleVersion: Long? = null,
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
	val allDay: Boolean,
	val date: LocalDate?,
	val scheduledAt: Instant?,
	val endsAt: Instant?,
	val timezone: String?,
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
	val status: String,
	val needsReview: Boolean,
	val source: String,
	val sourceType: String,
	val schedule: ApplicationScheduleSummaryResponse?,
	val memo: String,
)

data class ApplicationScheduleSummaryResponse(
	val nextActionTitle: String,
	val nextActionAt: LocalDate,
)

data class ApplicationListItemResponse(
	val id: UUID,
	val version: Long,
	val company: String,
	val position: String,
	val appliedAt: LocalDate,
	val status: String,
	val needsReview: Boolean,
	val source: String,
)

data class ApplicationListPageResponse(
	val items: List<ApplicationListItemResponse>,
	val page: Int,
	val limit: Int,
	val hasNext: Boolean,
	val filteredCount: Long,
	val totalCount: Long,
	val needsReviewCount: Long,
	val reviewRevision: Long,
)

data class ApplicationCountsResponse(val totalCount: Long)

data class CompleteBulkReviewRequest(
	val mutationId: UUID,
	@field:PositiveOrZero
	val expectedReviewRevision: Long,
)

data class CompleteBulkReviewResponse(
	val completedCount: Int,
	val needsReviewCount: Long,
	val reviewRevision: Long,
)

data class HistoryPageResponse<T>(
	val items: List<T>,
	val nextCursor: Long?,
	@field:JsonInclude(JsonInclude.Include.NON_NULL)
	val totalCount: Long? = null,
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

internal fun ApplicationResponse.compact(): ApplicationResponse = this
