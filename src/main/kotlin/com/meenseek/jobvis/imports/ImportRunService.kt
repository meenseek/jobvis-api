package com.meenseek.jobvis.imports

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ConflictException
import com.meenseek.jobvis.common.NotFoundException
import com.meenseek.jobvis.common.BusinessTime
import com.meenseek.jobvis.connection.ConnectionCapability
import com.meenseek.jobvis.connection.ConnectionProvider
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
import java.util.Locale
import java.util.UUID

@Service
class ImportRunService(
	private val runRepository: ImportRunRepository,
	private val connectionRepository: ExternalConnectionRepository,
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
	@Value("\${jobvis.import.retention:PT720H}") private val retention: Duration,
) {
	@Transactional
	fun create(userId: UUID, request: CreateImportRunRequest): ImportRunResponse =
		queue(userId, request.connectionId, request.dateFrom, request.dateTo, ImportRequestedBy.USER).toResponse()

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
		validateRange(dateFrom, dateTo)
		val connection = connectionRepository.findOwnedLocked(connectionId, userId) ?: return null
		if (connection.provider.capability != ConnectionCapability.MAIL ||
			connection.status != ConnectionStatus.CONNECTED || !connection.ongoingSyncConsent
		) return null
		if (!reserveGmailAccountImport(connection)) return null
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

	private fun queue(
		userId: UUID,
		connectionId: UUID,
		dateFrom: LocalDate,
		dateTo: LocalDate,
		requestedBy: ImportRequestedBy,
	): ImportRun {
		validateRange(dateFrom, dateTo)
		val connection = connectionRepository.findOwnedLocked(connectionId, userId)
			?: throw NotFoundException("외부 연결을 찾을 수 없습니다.")
		if (connection.provider.capability != ConnectionCapability.MAIL) {
			throw BadRequestException("메일 연결만 가져오기에 사용할 수 있습니다.")
		}
		if (connection.status != ConnectionStatus.CONNECTED) {
			throw ConflictException("외부 메일 연결을 다시 승인한 뒤 가져오기를 시작해 주세요.")
		}
		if (!reserveGmailAccountImport(connection)) {
			throw ConflictException("이 Gmail 계정에서 이미 가져오기가 진행 중입니다.")
		}
		if (runRepository.existsActive(userId, connectionId)) {
			throw ConflictException("이 메일 연결에서 이미 가져오기가 진행 중입니다.")
		}
		val now = Instant.now(clock)
		val run = ImportRun.queued(
			UUID.randomUUID(), userId, connectionId, connection.version, connection.provider, requestedBy,
			dateFrom, dateTo, now, now.plus(retention),
		)
		return try {
			runRepository.saveAndFlush(run)
		} catch (_: DataIntegrityViolationException) {
			throw ConflictException("이 메일 연결에서 이미 가져오기가 진행 중입니다.")
		}
	}

	private fun reserveGmailAccountImport(connection: ExternalConnection): Boolean {
		if (connection.provider != ConnectionProvider.GMAIL) return true
		val accountKey = connection.accountEmail.trim().lowercase(Locale.ROOT)
		jdbcTemplate.queryForList(
			"SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
			"jobvis:gmail-import:$accountKey",
		)
		val active = jdbcTemplate.queryForObject(
			"""
				SELECT EXISTS (
				    SELECT 1
				    FROM import_runs run
				    JOIN external_connections connection ON connection.id = run.connection_id
				    WHERE run.status IN ('QUEUED', 'RUNNING')
				      AND connection.provider = 'GMAIL'
				      AND lower(btrim(connection.account_email)) = ?
				)
			""".trimIndent(),
			Boolean::class.java,
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
	draftCount = draftCount,
	duplicateCount = duplicateCount,
	errorCode = errorCode,
	startedAt = startedAt,
	completedAt = completedAt,
	purgeAfter = purgeAfter,
	createdAt = createdAt,
)
