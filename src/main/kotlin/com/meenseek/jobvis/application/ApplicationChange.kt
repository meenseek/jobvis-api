package com.meenseek.jobvis.application

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "application_changes")
class ApplicationChange private constructor(
	@field:Id
	val id: UUID,
	@field:Column(name = "user_id", nullable = false)
	val userId: UUID,
	@field:Column(name = "application_id", nullable = false)
	val applicationId: UUID,
	@field:Column(name = "mutation_id", nullable = false)
	val mutationId: UUID,
	@field:Column(name = "field_key", nullable = false, length = 40)
	val fieldKey: String,
	@field:Column(nullable = false, length = 120)
	val title: String,
	@field:Column(name = "before_value", nullable = false, columnDefinition = "text")
	val beforeValue: String,
	@field:Column(name = "after_value", nullable = false, columnDefinition = "text")
	val afterValue: String,
	@field:Column(name = "occurred_at", nullable = false)
	val occurredAt: Instant,
	@field:Column(name = "created_at", nullable = false)
	val createdAt: Instant,
) {
	@field:Column(name = "recorded_order", nullable = false, insertable = false, updatable = false)
	val recordedOrder: Long = 0

	val description: String
		get() = "$beforeValue → $afterValue"

	companion object {
		fun create(
			id: UUID,
			userId: UUID,
			applicationId: UUID,
			mutationId: UUID,
			fieldKey: String,
			title: String,
			beforeValue: String,
			afterValue: String,
			occurredAt: Instant,
		): ApplicationChange = ApplicationChange(
			id = id,
			userId = userId,
			applicationId = applicationId,
			mutationId = mutationId,
			fieldKey = fieldKey,
			title = title,
			beforeValue = beforeValue,
			afterValue = afterValue,
			occurredAt = occurredAt,
			createdAt = occurredAt,
		)
	}
}
