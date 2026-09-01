package com.meenseek.jobvis.imports

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ConflictException
import com.meenseek.jobvis.common.NotFoundException
import com.meenseek.jobvis.common.TooManyRequestsException
import com.meenseek.jobvis.common.BusinessTime
import com.meenseek.jobvis.application.RequestFingerprint
import com.meenseek.jobvis.auth.UserAccountRepository
import com.meenseek.jobvis.connection.ConnectionCapability
import com.meenseek.jobvis.connection.ConnectionStatus
import com.meenseek.jobvis.connection.ExternalConnection
import com.meenseek.jobvis.connection.ExternalConnectionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

@Service
class ImportRunService(
	private val runRepository: ImportRunRepository,
	private val connectionRepository: ExternalConnectionRepository,
	private val userAccountRepository: UserAccountRepository,
	private val rolloutGate: MailImportRolloutGate,
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
	@Value("\${jobvis.import.retention:PT720H}") private val retention: Duration,
) {
	@Transactional
	fun create(userId: UUID, request: CreateImportRunRequest): ImportRunResponse {
		rolloutGate.requireReady()
		if ((request.dateFrom == null) != (request.dateTo == null)) {
			throw BadRequestException("가져오기 시작일과 종료일은 함께 입력해 주세요.")
		}
		val fingerprint = RequestFingerprint.of(
			"CREATE_IMPORT_RUN", request.connectionId,
			request.dateFrom?.toString().orEmpty(), request.dateTo?.toString().orEmpty(),
		)
		userAccountRepository.findLocked(userId)
			?: throw NotFoundException("사용자 정보를 찾을 수 없습니다.")
		runRepository.findByMutationLocked(userId, request.mutationId)?.let { existing ->
			if (existing.requestFingerprint != fingerprint) {
				throw ConflictException("이미 다른 요청에 사용된 mutationId입니다.")
			}
			return existing.toResponse()
		}
		val connection = connectionRepository.findOwnedLocked(request.connectionId, userId)
			?: throw NotFoundException("외부 연결을 찾을 수 없습니다.")
		val today = BusinessTime.today(clock)
		val dateTo = request.dateTo ?: today
		val dateFrom = request.dateFrom ?: connection.lastSyncedAt
			?.let { LocalDate.ofInstant(it, SEOUL).coerceAtMost(today).minusDays(1) }
			?.coerceAtLeast(today.minusYears(10))
			?: today.minusDays(7)
		return queueLocked(
			userId, connection, dateFrom, dateTo, ImportRequestedBy.USER,
			request.mutationId, fingerprint,
		).toResponse()
	}

	@Transactional(readOnly = true)
	fun list(userId: UUID, page: Int, size: Int): ImportPageResponse<ImportRunResponse> {
		val request = pageRequest(page, size)
		val slice = runRepository.findAllForUser(userId, request)
		return ImportPageResponse(slice.content.map(ImportRun::toResponse), page, size, slice.hasNext())
	}

	@Transactional(readOnly = true)
	fun get(userId: UUID, runId: UUID): ImportRunResponse = findOwned(userId, runId).toResponse()

	@Transactional
	fun cancel(userId: UUID, runId: UUID): ImportRunResponse {
		val now = Instant.now(clock)
		val cancelled = jdbcTemplate.update(
			"""
				UPDATE import_runs
				SET status = 'CANCELLED', completed_at = ?, updated_at = ?, error_code = NULL,
				    lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL
				WHERE id = ? AND user_id = ? AND status = 'QUEUED'
			""".trimIndent(),
			Timestamp.from(now), Timestamp.from(now), runId, userId,
		)
		val run = findOwned(userId, runId)
		if (cancelled == 0 && run.status != ImportRunStatus.CANCELLED) {
			throw ConflictException("대기 중인 가져오기만 취소할 수 있습니다.")
		}
		return run.toResponse()
	}

	@Transactional
	fun queueMonitor(
		userId: UUID,
		connectionId: UUID,
		dateFrom: LocalDate,
		dateTo: LocalDate,
	): ImportRun? {
		if (!rolloutGate.ready()) return null
		validateRange(dateFrom, dateTo)
		val connection = connectionRepository.findOwnedLocked(connectionId, userId) ?: return null
		if (connection.provider.capability != ConnectionCapability.MAIL ||
			connection.status != ConnectionStatus.CONNECTED || !connection.ongoingSyncConsent
		) return null
		if (!reserveProviderAccountImport(connection)) return null
		val id = UUID.randomUUID()
		val now = Instant.now(clock)
		val inserted = jdbcTemplate.update(
			"""
				INSERT INTO import_runs (
				    id, user_id, connection_id, connection_version, provider, requested_by, date_from, date_to,
				    status, provider_cursor, scanned_count, draft_count, duplicate_count,
				    error_code, started_at, completed_at, lease_owner, lease_expires_at,
				    heartbeat_at, attempt_count, purge_after, created_at, updated_at
				) VALUES (
				    ?, ?, ?, ?, ?, 'MONITOR', ?, ?, 'QUEUED', NULL, 0, 0, 0,
				    NULL, NULL, NULL, NULL, NULL, NULL, 0, ?, ?, ?
				)
				ON CONFLICT DO NOTHING
			""".trimIndent(),
			id, userId, connectionId, connection.version, connection.provider.name, dateFrom, dateTo,
			Timestamp.from(now.plus(retention)), Timestamp.from(now), Timestamp.from(now),
		)
		return if (inserted == 1) runRepository.findById(id).orElse(null) else null
	}

	private fun queueLocked(
		userId: UUID,
		connection: ExternalConnection,
		dateFrom: LocalDate,
		dateTo: LocalDate,
		requestedBy: ImportRequestedBy,
		mutationId: UUID?,
		requestFingerprint: String?,
	): ImportRun {
		validateRange(dateFrom, dateTo)
		val connectionId = connection.id
		if (connection.provider.capability != ConnectionCapability.MAIL) {
			throw BadRequestException("메일 연결만 가져오기에 사용할 수 있습니다.")
		}
		if (connection.status != ConnectionStatus.CONNECTED) {
			throw ConflictException("외부 메일 연결을 다시 승인한 뒤 가져오기를 시작해 주세요.")
		}
		if (runRepository.existsActive(userId, connectionId)) {
			throw ConflictException("이 메일 연결에서 이미 가져오기가 진행 중입니다.")
		}
		if (!reserveProviderAccountImport(connection)) {
			throw TooManyRequestsException("같은 메일 계정의 가져오기가 진행 중입니다.", ACCOUNT_BUSY_RETRY_SECONDS)
		}
		val now = Instant.now(clock)
		val run = ImportRun.queued(
			UUID.randomUUID(), userId, connectionId, connection.version, connection.provider, requestedBy,
			mutationId, requestFingerprint, dateFrom, dateTo, now, now.plus(retention),
		)
		return try {
			runRepository.saveAndFlush(run)
		} catch (_: DataIntegrityViolationException) {
			throw ConflictException("이 메일 연결에서 이미 가져오기가 진행 중입니다.")
		}
	}

	private fun reserveProviderAccountImport(connection: ExternalConnection): Boolean {
		val accountKey = connection.accountEmail.trim().lowercase(Locale.ROOT)
		jdbcTemplate.queryForList(
			"SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
			"jobvis:mail-import:${connection.provider.name}:$accountKey",
		)
		val active = jdbcTemplate.queryForObject(
			"""
				SELECT EXISTS (
				    SELECT 1
				    FROM import_runs run
				    JOIN external_connections connection ON connection.id = run.connection_id
				    WHERE run.status IN ('QUEUED', 'RUNNING')
				      AND connection.provider = ?
				      AND lower(btrim(connection.account_email)) = ?
				)
			""".trimIndent(),
			Boolean::class.java,
			connection.provider.name,
			accountKey,
		) == true
		return !active
	}

	private fun findOwned(userId: UUID, runId: UUID): ImportRun =
		runRepository.findOwned(runId, userId) ?: throw NotFoundException("가져오기 실행을 찾을 수 없습니다.")

	private fun validateRange(dateFrom: LocalDate, dateTo: LocalDate) {
		val today = BusinessTime.today(clock)
		if (dateFrom.isAfter(dateTo)) throw BadRequestException("가져오기 시작일은 종료일보다 늦을 수 없습니다.")
		if (dateTo.isAfter(today)) throw BadRequestException("미래 메일은 가져올 수 없습니다.")
		if (dateFrom.plusYears(10).isBefore(dateTo)) {
			throw BadRequestException("한 번에 가져올 수 있는 기간은 최대 10년입니다.")
		}
	}

	private fun pageRequest(page: Int, size: Int): PageRequest {
		if (page < 0 || size !in 1..100) throw BadRequestException("page는 0 이상, size는 1~100이어야 합니다.")
		return PageRequest.of(page, size)
	}

	private companion object {
		val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
		const val ACCOUNT_BUSY_RETRY_SECONDS = 30L
	}
}

internal fun ImportRun.toResponse(): ImportRunResponse = ImportRunResponse(
	id = id,
	connectionId = connectionId,
	connectionVersion = connectionVersion,
	provider = provider.apiValue(),
	requestedBy = requestedBy.name.lowercase(),
	dateFrom = dateFrom,
	dateTo = dateTo,
	status = status.name.lowercase(),
	scannedCount = scannedCount,
	finalizedCount = finalizedCount,
	ignoredCount = ignoredCount,
	duplicateCount = duplicateCount,
	errorCode = errorCode,
	startedAt = startedAt,
	completedAt = completedAt,
	purgeAfter = purgeAfter,
	createdAt = createdAt,
)
