package com.meenseek.jobvis.imports

import com.meenseek.jobvis.connection.ConnectionProvider
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "import_runs")
class ImportRun private constructor(
	id: UUID,
	userId: UUID,
	connectionId: UUID,
	connectionVersion: Long,
	provider: ConnectionProvider,
	requestedBy: ImportRequestedBy,
	mutationId: UUID?,
	requestFingerprint: String?,
	dateFrom: LocalDate,
	dateTo: LocalDate,
	status: ImportRunStatus,
	providerCursor: String?,
	scannedCount: Int,
	finalizedCount: Int,
	duplicateCount: Int,
	errorCode: String?,
	startedAt: Instant?,
	completedAt: Instant?,
	leaseOwner: UUID?,
	leaseExpiresAt: Instant?,
	heartbeatAt: Instant?,
	attemptCount: Int,
	purgeAfter: Instant,
	createdAt: Instant,
	updatedAt: Instant,
) {
	@field:Id
	@field:Column(name = "id", nullable = false)
	private var storedId: UUID = id

	@field:Column(name = "user_id", nullable = false)
	private var storedUserId: UUID = userId

	@field:Column(name = "connection_id", nullable = false)
	private var storedConnectionId: UUID = connectionId

	@field:Column(name = "connection_version", nullable = false)
	private var storedConnectionVersion: Long = connectionVersion

	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "provider", nullable = false, length = 30)
	private var storedProvider: ConnectionProvider = provider

	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "requested_by", nullable = false, length = 20)
	private var storedRequestedBy: ImportRequestedBy = requestedBy

	@field:Column(name = "mutation_id")
	private var storedMutationId: UUID? = mutationId

	@field:Column(name = "request_fingerprint", length = 64)
	private var storedRequestFingerprint: String? = requestFingerprint

	@field:Column(name = "date_from", nullable = false)
	private var storedDateFrom: LocalDate = dateFrom

	@field:Column(name = "date_to", nullable = false)
	private var storedDateTo: LocalDate = dateTo

	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "status", nullable = false, length = 20)
	private var storedStatus: ImportRunStatus = status

	@field:Column(name = "provider_cursor", columnDefinition = "text")
	private var storedProviderCursor: String? = providerCursor

	@field:Column(name = "scanned_count", nullable = false)
	private var storedScannedCount: Int = scannedCount

	@field:Column(name = "draft_count", nullable = false)
	private var storedFinalizedCount: Int = finalizedCount

	@field:Column(name = "duplicate_count", nullable = false)
	private var storedDuplicateCount: Int = duplicateCount

	@field:Column(name = "error_code", length = 80)
	private var storedErrorCode: String? = errorCode

	@field:Column(name = "started_at")
	private var storedStartedAt: Instant? = startedAt

	@field:Column(name = "completed_at")
	private var storedCompletedAt: Instant? = completedAt

	@field:Column(name = "lease_owner")
	private var storedLeaseOwner: UUID? = leaseOwner

	@field:Column(name = "lease_expires_at")
	private var storedLeaseExpiresAt: Instant? = leaseExpiresAt

	@field:Column(name = "heartbeat_at")
	private var storedHeartbeatAt: Instant? = heartbeatAt

	@field:Column(name = "attempt_count", nullable = false)
	private var storedAttemptCount: Int = attemptCount

	@field:Column(name = "purge_after", nullable = false)
	private var storedPurgeAfter: Instant = purgeAfter

	@field:Column(name = "created_at", nullable = false)
	private var storedCreatedAt: Instant = createdAt

	@field:Column(name = "updated_at", nullable = false)
	private var storedUpdatedAt: Instant = updatedAt

	val id: UUID get() = storedId
	val userId: UUID get() = storedUserId
	val connectionId: UUID get() = storedConnectionId
	val connectionVersion: Long get() = storedConnectionVersion
	val provider: ConnectionProvider get() = storedProvider
	val requestedBy: ImportRequestedBy get() = storedRequestedBy
	val requestFingerprint: String? get() = storedRequestFingerprint
	val dateFrom: LocalDate get() = storedDateFrom
	val dateTo: LocalDate get() = storedDateTo
	val status: ImportRunStatus get() = storedStatus
	val scannedCount: Int get() = storedScannedCount
	val finalizedCount: Int get() = storedFinalizedCount
	val ignoredCount: Int get() = (storedScannedCount - storedFinalizedCount - storedDuplicateCount).coerceAtLeast(0)
	val duplicateCount: Int get() = storedDuplicateCount
	val errorCode: String? get() = storedErrorCode
	val startedAt: Instant? get() = storedStartedAt
	val completedAt: Instant? get() = storedCompletedAt
	val leaseOwner: UUID? get() = storedLeaseOwner
	val leaseExpiresAt: Instant? get() = storedLeaseExpiresAt
	val heartbeatAt: Instant? get() = storedHeartbeatAt
	val attemptCount: Int get() = storedAttemptCount
	val purgeAfter: Instant get() = storedPurgeAfter
	val createdAt: Instant get() = storedCreatedAt

	fun complete(scannedCount: Int, finalizedCount: Int, duplicateCount: Int, now: Instant) {
		require(storedStatus == ImportRunStatus.RUNNING) { "실행 중인 가져오기만 완료할 수 있습니다." }
		storedScannedCount = scannedCount
		storedFinalizedCount = finalizedCount
		storedDuplicateCount = duplicateCount
		storedStatus = ImportRunStatus.COMPLETED
		storedCompletedAt = now
		storedUpdatedAt = now
		storedErrorCode = null
		clearLease()
	}

	fun fail(errorCode: String, now: Instant) {
		storedStatus = ImportRunStatus.FAILED
		storedErrorCode = errorCode.take(80)
		storedCompletedAt = now
		storedUpdatedAt = now
		clearLease()
	}

	fun cancel(errorCode: String? = null, now: Instant) {
		if (storedStatus != ImportRunStatus.QUEUED && storedStatus != ImportRunStatus.RUNNING) return
		storedStatus = ImportRunStatus.CANCELLED
		storedErrorCode = errorCode?.take(80)
		storedCompletedAt = now
		storedUpdatedAt = now
		clearLease()
	}

	private fun clearLease() {
		storedLeaseOwner = null
		storedLeaseExpiresAt = null
		storedHeartbeatAt = null
	}

	companion object {
		fun queued(
			id: UUID,
			userId: UUID,
			connectionId: UUID,
			connectionVersion: Long,
			provider: ConnectionProvider,
			requestedBy: ImportRequestedBy,
			mutationId: UUID?,
			requestFingerprint: String?,
			dateFrom: LocalDate,
			dateTo: LocalDate,
			now: Instant,
			purgeAfter: Instant,
		): ImportRun = ImportRun(
			id, userId, connectionId, connectionVersion, provider, requestedBy, mutationId, requestFingerprint,
			dateFrom, dateTo,
			ImportRunStatus.QUEUED, null, 0, 0, 0, null, null, null,
			null, null, null, 0, purgeAfter, now, now,
		)
	}
}
