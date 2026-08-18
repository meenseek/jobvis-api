package com.meenseek.jobvis.imports

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateImportRunRequest(
	val connectionId: UUID,
	val dateFrom: LocalDate,
	val dateTo: LocalDate,
)

data class ImportPageResponse<T>(
	val items: List<T>,
	val page: Int,
	val size: Int,
	val hasNext: Boolean,
)

data class ImportRunResponse(
	val id: UUID,
	val connectionId: UUID,
	val connectionVersion: Long,
	val provider: String,
	val requestedBy: String,
	val dateFrom: LocalDate,
	val dateTo: LocalDate,
	val status: String,
	val scannedCount: Int,
	val draftCount: Int,
	val duplicateCount: Int,
	val errorCode: String?,
	val startedAt: Instant?,
	val completedAt: Instant?,
	val purgeAfter: Instant,
	val createdAt: Instant,
)

data class ImportDraftResponse(
	val id: UUID,
	val runId: UUID,
	val connectionId: UUID,
	val provider: String,
	val providerMessageId: String,
	val subject: String,
	val sender: String,
	val receivedAt: Instant,
	val sourceSummary: String,
	val company: String,
	val position: String,
	val location: String,
	val employmentType: String,
	val appliedAt: LocalDate,
	val stage: String,
	val highestStageReached: String,
	val screeningPassed: Boolean,
	val result: String,
	val scheduleType: String?,
	val scheduleAction: String?,
	val scheduledAt: Instant?,
	val scheduleEndsAt: Instant?,
	val confidence: BigDecimal,
	val status: String,
	val acceptedApplicationId: UUID?,
	val version: Long,
	val decidedAt: Instant?,
)

data class UpdateImportDraftRequest(
	@field:PositiveOrZero
	val expectedVersion: Long,
	@field:NotBlank
	@field:Size(max = 160)
	val company: String,
	@field:NotBlank
	@field:Size(max = 160)
	val position: String,
	@field:NotBlank
	@field:Size(max = 160)
	val location: String,
	@field:NotBlank
	@field:Size(max = 80)
	val employmentType: String,
	val appliedAt: LocalDate,
	@field:NotBlank
	val stage: String,
	@field:NotBlank
	val highestStageReached: String,
	val screeningPassed: Boolean,
	@field:NotBlank
	val result: String,
	val scheduleType: String?,
	@field:Size(max = 200)
	val scheduleAction: String?,
	val scheduledAt: Instant?,
	val scheduleEndsAt: Instant?,
)

data class DecideImportDraftRequest(
	val mutationId: UUID,
	@field:PositiveOrZero
	val expectedVersion: Long,
)

data class AcceptImportDraftRequest(
	val mutationId: UUID,
	@field:PositiveOrZero
	val expectedVersion: Long,
	val targetApplicationId: UUID? = null,
	@field:PositiveOrZero
	val expectedApplicationVersion: Long? = null,
	@field:PositiveOrZero
	val expectedScheduleVersion: Long? = null,
)
