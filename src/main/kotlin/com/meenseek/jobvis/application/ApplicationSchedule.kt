package com.meenseek.jobvis.application

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "application_schedules")
class ApplicationSchedule private constructor(
	id: UUID,
	userId: UUID,
	applicationId: UUID,
	scheduleType: ScheduleType,
	action: String,
	scheduledAt: Instant?,
	completed: Boolean,
	completedAt: Instant?,
	endsAt: Instant?,
	timezone: String,
	location: String,
	description: String,
	lastImportReceivedAt: Instant?,
	manuallyEdited: Boolean,
	version: Long,
	createdAt: Instant,
	updatedAt: Instant,
) {
	@field:Id
	@field:Column(name = "id", nullable = false)
	private var storedId: UUID = id

	@field:Column(name = "user_id", nullable = false)
	private var storedUserId: UUID = userId

	@field:Column(name = "application_id", nullable = false)
	private var storedApplicationId: UUID = applicationId

	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "schedule_type", nullable = false, length = 20)
	private var storedScheduleType: ScheduleType = scheduleType

	@field:Column(name = "action", nullable = false, length = 200)
	private var storedAction: String = action

	@field:Column(name = "scheduled_at")
	private var storedScheduledAt: Instant? = scheduledAt

	@field:Column(name = "completed", nullable = false)
	private var storedCompleted: Boolean = completed

	@field:Column(name = "completed_at")
	private var storedCompletedAt: Instant? = completedAt

	@field:Column(name = "ends_at")
	private var storedEndsAt: Instant? = endsAt

	@field:Column(name = "timezone", nullable = false, length = 80)
	private var storedTimezone: String = timezone

	@field:Column(name = "location", nullable = false, length = 300)
	private var storedLocation: String = location

	@field:Column(name = "description", nullable = false, columnDefinition = "text")
	private var storedDescription: String = description

	@field:Column(name = "last_import_received_at")
	private var storedLastImportReceivedAt: Instant? = lastImportReceivedAt

	@field:Column(name = "manually_edited", nullable = false)
	private var storedManuallyEdited: Boolean = manuallyEdited

	@field:Version
	@field:Column(name = "version", nullable = false)
	private var storedVersion: Long = version

	@field:Column(name = "created_at", nullable = false)
	private var storedCreatedAt: Instant = createdAt

	@field:Column(name = "updated_at", nullable = false)
	private var storedUpdatedAt: Instant = updatedAt

	val id: UUID get() = storedId
	val applicationId: UUID get() = storedApplicationId
	val scheduleType: ScheduleType get() = storedScheduleType
	val action: String get() = storedAction
	val scheduledAt: Instant? get() = storedScheduledAt
	val completed: Boolean get() = storedCompleted
	val completedAt: Instant? get() = storedCompletedAt
	val endsAt: Instant? get() = storedEndsAt
	val timezone: String get() = storedTimezone
	val location: String get() = storedLocation
	val description: String get() = storedDescription
	val lastImportReceivedAt: Instant? get() = storedLastImportReceivedAt
	val manuallyEdited: Boolean get() = storedManuallyEdited
	val version: Long get() = storedVersion

	fun complete(now: Instant) {
		storedCompleted = true
		storedCompletedAt = now
		storedUpdatedAt = now
	}

	fun update(
		scheduleType: ScheduleType,
		action: String,
		scheduledAt: Instant,
		endsAt: Instant?,
		timezone: String,
		location: String,
		description: String,
		now: Instant,
	): Boolean {
		if (storedScheduleType == scheduleType && storedAction == action && storedScheduledAt == scheduledAt &&
			storedEndsAt == endsAt && storedTimezone == timezone && storedLocation == location &&
			storedDescription == description && !storedCompleted && storedCompletedAt == null
		) return false
		storedScheduleType = scheduleType
		storedAction = action
		storedScheduledAt = scheduledAt
		storedEndsAt = endsAt
		storedTimezone = timezone
		storedLocation = location
		storedDescription = description
		storedCompleted = false
		storedCompletedAt = null
		storedManuallyEdited = true
		storedUpdatedAt = now
		return true
	}

	fun mergeImported(
		scheduleType: ScheduleType,
		action: String,
		scheduledAt: Instant,
		endsAt: Instant?,
		receivedAt: Instant,
		now: Instant,
	): Boolean {
		if (storedManuallyEdited || storedLastImportReceivedAt?.let { !receivedAt.isAfter(it) } == true ||
			storedCompletedAt?.let { !receivedAt.isAfter(it) } == true
		) return false
		storedScheduleType = scheduleType
		storedAction = action
		storedScheduledAt = scheduledAt
		storedEndsAt = endsAt
		storedCompleted = false
		storedCompletedAt = null
		storedLastImportReceivedAt = receivedAt
		storedUpdatedAt = now
		return true
	}

	companion object {
		fun createDefault(id: UUID, userId: UUID, applicationId: UUID, now: Instant): ApplicationSchedule =
			ApplicationSchedule(
				id = id,
				userId = userId,
				applicationId = applicationId,
				scheduleType = ScheduleType.APPLICATION,
				action = "세부 정보 보완",
				scheduledAt = now,
				completed = false,
				completedAt = null,
				endsAt = now.plusSeconds(3600),
				timezone = "Asia/Seoul",
				location = "",
				description = "",
				lastImportReceivedAt = null,
				manuallyEdited = false,
				version = 0,
				createdAt = now,
				updatedAt = now,
			)

		fun createImported(
			id: UUID,
			userId: UUID,
			applicationId: UUID,
			scheduleType: ScheduleType,
			action: String,
			scheduledAt: Instant,
			endsAt: Instant?,
			receivedAt: Instant,
			now: Instant,
		): ApplicationSchedule = ApplicationSchedule(
			id = id,
			userId = userId,
			applicationId = applicationId,
			scheduleType = scheduleType,
			action = action,
			scheduledAt = scheduledAt,
			completed = false,
			completedAt = null,
			endsAt = endsAt,
			timezone = "Asia/Seoul",
			location = "",
			description = "메일에서 추출한 일정입니다.",
			lastImportReceivedAt = receivedAt,
			manuallyEdited = false,
			version = 0,
			createdAt = now,
			updatedAt = now,
		)
	}
}
