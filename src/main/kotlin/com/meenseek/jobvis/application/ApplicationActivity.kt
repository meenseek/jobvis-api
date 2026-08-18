package com.meenseek.jobvis.application

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "application_activities")
class ApplicationActivity private constructor(
	@field:Id
	val id: UUID,
	@field:Column(name = "user_id", nullable = false)
	val userId: UUID,
	@field:Column(name = "application_id", nullable = false)
	val applicationId: UUID,
	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "activity_type", nullable = false, length = 20)
	val activityType: ActivityType,
	@field:Column(nullable = false, length = 120)
	val title: String,
	@field:Column(nullable = false, columnDefinition = "text")
	val description: String,
	@field:Column(name = "occurred_at", nullable = false)
	val occurredAt: Instant,
	@field:Column(name = "created_at", nullable = false)
	val createdAt: Instant,
) {
	@field:Column(name = "recorded_order", nullable = false, insertable = false, updatable = false)
	val recordedOrder: Long = 0

	companion object {
		fun create(
			id: UUID,
			userId: UUID,
			applicationId: UUID,
			activityType: ActivityType,
			title: String,
			description: String,
			occurredAt: Instant,
			createdAt: Instant = occurredAt,
		): ApplicationActivity = ApplicationActivity(
			id = id,
			userId = userId,
			applicationId = applicationId,
			activityType = activityType,
			title = title,
			description = description,
			occurredAt = occurredAt,
			createdAt = createdAt,
		)
	}
}
