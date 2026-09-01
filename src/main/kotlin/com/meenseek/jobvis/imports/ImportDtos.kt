package com.meenseek.jobvis.imports

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateImportRunRequest(
	val mutationId: UUID,
	val connectionId: UUID,
	val dateFrom: LocalDate? = null,
	val dateTo: LocalDate? = null,
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
	val finalizedCount: Int,
	val ignoredCount: Int,
	val duplicateCount: Int,
	val errorCode: String?,
	val startedAt: Instant?,
	val completedAt: Instant?,
	val purgeAfter: Instant,
	val createdAt: Instant,
)
