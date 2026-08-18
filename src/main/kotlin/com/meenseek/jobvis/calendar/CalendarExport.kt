package com.meenseek.jobvis.calendar

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class CalendarExportStatus { PREVIEWED, CONFIRMING, CONFIRMED, FAILED }

@Entity
@Table(name = "calendar_exports")
class CalendarExport private constructor(
	id: UUID,
	userId: UUID,
	scheduleId: UUID,
	connectionId: UUID,
	scheduleVersion: Long,
	previewHash: String,
	idempotencyKey: UUID,
	title: String,
	startsAt: Instant,
	endsAt: Instant,
	timezone: String,
	location: String,
	description: String,
	status: CalendarExportStatus,
	providerEventId: String?,
	lastErrorCode: String?,
	confirmedAt: Instant?,
	claimToken: UUID?,
	claimExpiresAt: Instant?,
	createdAt: Instant,
	updatedAt: Instant,
) {
	@field:Id
	@field:Column(name = "id", nullable = false)
	private var storedId: UUID = id
	@field:Column(name = "user_id", nullable = false)
	private var storedUserId: UUID = userId
	@field:Column(name = "schedule_id", nullable = false)
	private var storedScheduleId: UUID = scheduleId
	@field:Column(name = "connection_id", nullable = false)
	private var storedConnectionId: UUID = connectionId
	@field:Column(name = "schedule_version", nullable = false)
	private var storedScheduleVersion: Long = scheduleVersion
	@field:Column(name = "preview_hash", nullable = false, length = 64)
	private var storedPreviewHash: String = previewHash
	@field:Column(name = "idempotency_key", nullable = false)
	private var storedIdempotencyKey: UUID = idempotencyKey
	@field:Column(name = "title", nullable = false, length = 200)
	private var storedTitle: String = title
	@field:Column(name = "starts_at", nullable = false)
	private var storedStartsAt: Instant = startsAt
	@field:Column(name = "ends_at", nullable = false)
	private var storedEndsAt: Instant = endsAt
	@field:Column(name = "timezone", nullable = false, length = 80)
	private var storedTimezone: String = timezone
	@field:Column(name = "location", nullable = false, length = 300)
	private var storedLocation: String = location
	@field:Column(name = "description", nullable = false, columnDefinition = "text")
	private var storedDescription: String = description
	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "status", nullable = false, length = 20)
	private var storedStatus: CalendarExportStatus = status
	@field:Column(name = "provider_event_id", length = 255)
	private var storedProviderEventId: String? = providerEventId
	@field:Column(name = "last_error_code", length = 80)
	private var storedLastErrorCode: String? = lastErrorCode
	@field:Column(name = "confirmed_at")
	private var storedConfirmedAt: Instant? = confirmedAt
	@field:Column(name = "claim_token")
	private var storedClaimToken: UUID? = claimToken
	@field:Column(name = "claim_expires_at")
	private var storedClaimExpiresAt: Instant? = claimExpiresAt
	@field:Column(name = "created_at", nullable = false)
	private var storedCreatedAt: Instant = createdAt
	@field:Column(name = "updated_at", nullable = false)
	private var storedUpdatedAt: Instant = updatedAt

	val id: UUID get() = storedId
	val scheduleId: UUID get() = storedScheduleId
	val connectionId: UUID get() = storedConnectionId
	val scheduleVersion: Long get() = storedScheduleVersion
	val previewHash: String get() = storedPreviewHash
	val idempotencyKey: UUID get() = storedIdempotencyKey
	val title: String get() = storedTitle
	val startsAt: Instant get() = storedStartsAt
	val endsAt: Instant get() = storedEndsAt
	val timezone: String get() = storedTimezone
	val location: String get() = storedLocation
	val description: String get() = storedDescription
	val status: CalendarExportStatus get() = storedStatus
	val providerEventId: String? get() = storedProviderEventId
	val lastErrorCode: String? get() = storedLastErrorCode
	val confirmedAt: Instant? get() = storedConfirmedAt
	val claimToken: UUID? get() = storedClaimToken
	val claimExpiresAt: Instant? get() = storedClaimExpiresAt
	val createdAt: Instant get() = storedCreatedAt

	fun claim(token: UUID, expiresAt: Instant, now: Instant) {
		storedStatus = CalendarExportStatus.CONFIRMING
		storedClaimToken = token
		storedClaimExpiresAt = expiresAt
		storedLastErrorCode = null
		storedUpdatedAt = now
	}

	fun confirm(token: UUID, providerEventId: String, now: Instant) {
		require(storedStatus == CalendarExportStatus.CONFIRMING && storedClaimToken == token) {
			"현재 캘린더 내보내기 claim만 완료할 수 있습니다."
		}
		storedStatus = CalendarExportStatus.CONFIRMED
		storedProviderEventId = providerEventId.take(255)
		storedLastErrorCode = null
		storedConfirmedAt = now
		storedUpdatedAt = now
		clearClaim()
	}

	fun fail(token: UUID, errorCode: String, now: Instant) {
		require(storedStatus == CalendarExportStatus.CONFIRMING && storedClaimToken == token) {
			"현재 캘린더 내보내기 claim만 실패 처리할 수 있습니다."
		}
		storedStatus = CalendarExportStatus.FAILED
		storedProviderEventId = null
		storedLastErrorCode = errorCode.take(80)
		storedConfirmedAt = null
		storedUpdatedAt = now
		clearClaim()
	}

	private fun clearClaim() {
		storedClaimToken = null
		storedClaimExpiresAt = null
	}

	companion object {
		fun previewed(
			id: UUID,
			userId: UUID,
			scheduleId: UUID,
			connectionId: UUID,
			scheduleVersion: Long,
			previewHash: String,
			idempotencyKey: UUID,
			title: String,
			startsAt: Instant,
			endsAt: Instant,
			timezone: String,
			location: String,
			description: String,
			now: Instant,
		): CalendarExport = CalendarExport(
			id, userId, scheduleId, connectionId, scheduleVersion, previewHash, idempotencyKey,
			title, startsAt, endsAt, timezone, location, description,
			CalendarExportStatus.PREVIEWED, null, null, null, null, null, now, now,
		)
	}
}
