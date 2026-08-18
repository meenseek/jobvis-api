package com.meenseek.jobvis.application

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "application_mutations")
class ApplicationMutation private constructor(
	id: UUID,
	userId: UUID,
	mutationId: UUID,
	applicationId: UUID?,
	operation: String,
	requestFingerprint: String,
	resultingVersion: Long?,
	historyWatermark: Long?,
	resultPayload: String?,
	createdAt: Instant,
	completedAt: Instant?,
) {
	@field:Id
	@field:Column(name = "id", nullable = false)
	private var storedId: UUID = id

	@field:Column(name = "user_id", nullable = false)
	private var storedUserId: UUID = userId

	@field:Column(name = "mutation_id", nullable = false)
	private var storedMutationId: UUID = mutationId

	@field:Column(name = "application_id")
	private var storedApplicationId: UUID? = applicationId

	@field:Column(name = "operation", nullable = false, length = 40)
	private var storedOperation: String = operation

	@field:Column(name = "request_fingerprint", nullable = false, length = 64)
	private var storedRequestFingerprint: String = requestFingerprint

	@field:Column(name = "resulting_version")
	private var storedResultingVersion: Long? = resultingVersion

	@field:Column(name = "history_watermark")
	private var storedHistoryWatermark: Long? = historyWatermark

	@field:Column(name = "result_payload", columnDefinition = "text")
	private var storedResultPayload: String? = resultPayload

	@field:Column(name = "created_at", nullable = false)
	private var storedCreatedAt: Instant = createdAt

	@field:Column(name = "completed_at")
	private var storedCompletedAt: Instant? = completedAt

	val applicationId: UUID?
		get() = storedApplicationId

	val completed: Boolean
		get() = storedCompletedAt != null

	val resultPayload: String?
		get() = storedResultPayload

	val historyWatermark: Long?
		get() = storedHistoryWatermark

	val completedAt: Instant?
		get() = storedCompletedAt

	fun matchesRequest(expectedOperation: String, expectedFingerprint: String): Boolean =
		storedOperation == expectedOperation && storedRequestFingerprint == expectedFingerprint

	fun complete(
		applicationId: UUID,
		resultingVersion: Long,
		historyWatermark: Long,
		resultPayload: String,
		now: Instant,
	) {
		check(!completed) { "완료된 mutation은 다시 완료할 수 없습니다." }
		storedApplicationId = applicationId
		storedResultingVersion = resultingVersion
		storedHistoryWatermark = historyWatermark
		storedResultPayload = resultPayload
		storedCompletedAt = now
	}

}
