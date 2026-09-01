package com.meenseek.jobvis.calendar

import com.meenseek.jobvis.application.ApplicationSchedule
import com.meenseek.jobvis.application.ApplicationScheduleRepository
import com.meenseek.jobvis.application.JobApplication
import com.meenseek.jobvis.application.JobApplicationRepository
import com.meenseek.jobvis.application.RequestFingerprint
import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ConflictException
import com.meenseek.jobvis.common.NotFoundException
import com.meenseek.jobvis.common.ServiceUnavailableException
import com.meenseek.jobvis.common.ExternalConnectionAuthorizationException
import com.meenseek.jobvis.connection.ConnectionCredentialService
import com.meenseek.jobvis.connection.ConnectionProvider
import com.meenseek.jobvis.connection.ConnectionService
import com.meenseek.jobvis.connection.ConnectionStatus
import com.meenseek.jobvis.connection.ConnectionStateService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class CalendarExportService(
	private val exportRepository: CalendarExportRepository,
	private val scheduleRepository: ApplicationScheduleRepository,
	private val applicationRepository: JobApplicationRepository,
	private val connectionService: ConnectionService,
	private val credentialService: ConnectionCredentialService,
	private val googleCalendarClient: GoogleCalendarClient,
	private val transactionService: CalendarExportTransactionService,
	private val snapshotFactory: CalendarSnapshotFactory,
	private val connectionStateService: ConnectionStateService,
	private val clock: Clock,
) {
	@Transactional
	fun preview(userId: UUID, request: CreateCalendarPreviewRequest): CalendarExportResponse {
		val schedule = scheduleRepository.findOwned(request.scheduleId, userId)
			?: throw NotFoundException("일정을 찾을 수 없습니다.")
		if (schedule.version != request.expectedScheduleVersion) {
			throw ConflictException("일정이 다른 곳에서 변경되었습니다. 최신 내용을 확인해 주세요.")
		}
		val application = findApplication(userId, schedule.applicationId)
		validateCalendarConnection(userId, request.connectionId)
		val snapshot = snapshotFactory.create(schedule, application, request.connectionId)
		val timezone = schedule.timezone
			?: throw BadRequestException("종일 일정은 시각과 시간대를 지정한 뒤 내보내 주세요.")
		exportRepository.findByIdempotencyKey(userId, request.idempotencyKey)?.let { existing ->
			if (existing.scheduleId != request.scheduleId || existing.previewHash != snapshot.previewHash) {
				throw ConflictException("이미 다른 캘린더 미리보기에 사용된 idempotencyKey입니다.")
			}
			return existing.toResponse()
		}
		val now = Instant.now(clock)
		exportRepository.reservePreview(
			UUID.randomUUID(), userId, schedule.id, request.connectionId, schedule.version,
			snapshot.previewHash, request.idempotencyKey, snapshot.title, snapshot.startsAt,
			snapshot.endsAt, timezone, schedule.location, snapshot.description, now,
		)
		exportRepository.findByIdempotencyKey(userId, request.idempotencyKey)?.let { existing ->
			if (existing.scheduleId != request.scheduleId || existing.previewHash != snapshot.previewHash) {
				throw ConflictException("이미 다른 캘린더 미리보기에 사용된 idempotencyKey입니다.")
			}
			return existing.toResponse()
		}
		throw IllegalStateException("예약한 idempotencyKey의 캘린더 미리보기를 찾을 수 없습니다.")
	}

	@Transactional(readOnly = true)
	fun get(userId: UUID, exportId: UUID): CalendarExportResponse =
		findOwned(userId, exportId).toResponse()

	fun confirm(
		userId: UUID,
		exportId: UUID,
		request: ConfirmCalendarExportRequest,
	): CalendarExportResponse {
		return when (val result = transactionService.claim(userId, exportId, request.previewHash.trim())) {
			is CalendarClaimResult.AlreadyConfirmed -> result.response
			is CalendarClaimResult.Claimed -> {
				var credentialVersion: Long? = null
				try {
					val credential = credentialService.authorizedAccessToken(userId, result.connectionId)
					credentialVersion = credential.connectionVersion
					val createdEventId = googleCalendarClient.insert(credential.value, result.event)
					transactionService.confirm(userId, exportId, result.claimToken, createdEventId)
				} catch (exception: ExternalConnectionAuthorizationException) {
					transactionService.fail(userId, exportId, result.claimToken, "CALENDAR_REAUTHORIZATION_REQUIRED")
					connectionStateService.markReauthorizationRequired(
						userId, result.connectionId, "CALENDAR_REAUTHORIZATION_REQUIRED", Instant.now(clock),
						exception.connectionVersion,
					)
					throw exception
				} catch (exception: CalendarProviderException) {
					transactionService.fail(userId, exportId, result.claimToken, exception.errorCode)
					if (exception.reauthorizationRequired) {
						connectionStateService.markReauthorizationRequired(
							userId, result.connectionId, exception.errorCode, Instant.now(clock), credentialVersion,
						)
					}
					throw ServiceUnavailableException(
						"Google Calendar에 일정을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.",
					)
				} catch (exception: ServiceUnavailableException) {
					transactionService.fail(
						userId, exportId, result.claimToken, "CALENDAR_CREDENTIAL_TEMPORARILY_UNAVAILABLE",
					)
					throw exception
				}
			}
		}
	}

	private fun validateCalendarConnection(userId: UUID, connectionId: UUID) {
		val connection = connectionService.findOwned(userId, connectionId)
		if (connection.provider != ConnectionProvider.GOOGLE_CALENDAR || connection.status != ConnectionStatus.CONNECTED) {
			throw BadRequestException("승인된 Google Calendar 연결을 선택해 주세요.")
		}
	}

	private fun findApplication(userId: UUID, applicationId: UUID): JobApplication =
		applicationRepository.findOwned(applicationId, userId)
			?: throw NotFoundException("지원 정보를 찾을 수 없습니다.")

	private fun findOwned(userId: UUID, exportId: UUID): CalendarExport =
		exportRepository.findOwned(exportId, userId)
			?: throw NotFoundException("캘린더 내보내기 미리보기를 찾을 수 없습니다.")

}

@Service
class CalendarExportTransactionService(
	private val exportRepository: CalendarExportRepository,
	private val scheduleRepository: ApplicationScheduleRepository,
	private val applicationRepository: JobApplicationRepository,
	private val connectionService: ConnectionService,
	private val snapshotFactory: CalendarSnapshotFactory,
	private val clock: Clock,
	@Value("\${jobvis.external-http.connect-timeout:PT5S}") externalConnectTimeout: Duration,
	@Value("\${jobvis.external-http.read-timeout:PT30S}") externalReadTimeout: Duration,
) {
	private val claimDuration = calendarClaimDuration(externalConnectTimeout, externalReadTimeout)

	@Transactional
	fun claim(userId: UUID, exportId: UUID, previewHash: String): CalendarClaimResult {
		val export = exportRepository.findOwnedLocked(exportId, userId)
			?: throw NotFoundException("캘린더 내보내기 미리보기를 찾을 수 없습니다.")
		if (export.previewHash != previewHash) {
			throw ConflictException("확인한 미리보기와 요청 내용이 일치하지 않습니다.")
		}
		if (export.status == CalendarExportStatus.CONFIRMED) {
			return CalendarClaimResult.AlreadyConfirmed(export.toResponse())
		}
		val now = Instant.now(clock)
		if (export.status == CalendarExportStatus.CONFIRMING && export.claimExpiresAt?.isAfter(now) == true) {
			throw ConflictException("캘린더 내보내기가 이미 처리 중입니다.")
		}
		val schedule = scheduleRepository.findOwnedLocked(export.scheduleId, userId)
			?: throw NotFoundException("일정을 찾을 수 없습니다.")
		if (schedule.version != export.scheduleVersion) {
			throw ConflictException("미리보기 이후 일정이 변경되었습니다. 새 미리보기를 만들어 주세요.")
		}
		val application = applicationRepository.findOwned(schedule.applicationId, userId)
			?: throw NotFoundException("지원 정보를 찾을 수 없습니다.")
		val snapshot = snapshotFactory.create(schedule, application, export.connectionId)
		if (snapshot.previewHash != export.previewHash) {
			throw ConflictException("미리보기 이후 지원 정보가 변경되었습니다. 새 미리보기를 만들어 주세요.")
		}
		val connection = connectionService.findOwned(userId, export.connectionId)
		if (connection.provider != ConnectionProvider.GOOGLE_CALENDAR ||
			connection.status != ConnectionStatus.CONNECTED
		) {
			throw BadRequestException("승인된 Google Calendar 연결을 선택해 주세요.")
		}
		val claimToken = UUID.randomUUID()
		export.claim(claimToken, now.plus(claimDuration), now)
		exportRepository.saveAndFlush(export)
		return CalendarClaimResult.Claimed(
			claimToken,
			export.connectionId,
			GoogleCalendarEvent(
				deterministicProviderEventId(export), export.title, export.startsAt.toString(),
				export.endsAt.toString(), export.timezone, export.location, export.description,
			),
		)
	}

	@Transactional
	fun confirm(userId: UUID, exportId: UUID, claimToken: UUID, providerEventId: String): CalendarExportResponse {
		val export = exportRepository.findOwnedLocked(exportId, userId)
			?: throw NotFoundException("캘린더 내보내기 미리보기를 찾을 수 없습니다.")
		if (export.status == CalendarExportStatus.CONFIRMED) return export.toResponse()
		export.confirm(claimToken, providerEventId, Instant.now(clock))
		return exportRepository.saveAndFlush(export).toResponse()
	}

	@Transactional
	fun fail(userId: UUID, exportId: UUID, claimToken: UUID, errorCode: String) {
		val export = exportRepository.findOwnedLocked(exportId, userId) ?: return
		if (export.status != CalendarExportStatus.CONFIRMING || export.claimToken != claimToken) return
		export.fail(claimToken, errorCode, Instant.now(clock))
		exportRepository.saveAndFlush(export)
	}
}

@Component
class CalendarSnapshotFactory {
	fun create(
		schedule: ApplicationSchedule,
		application: JobApplication,
		connectionId: UUID,
	): CalendarSnapshot {
		if (schedule.allDay) {
			throw BadRequestException("종일 일정은 시각과 시간대를 지정한 뒤 내보내 주세요.")
		}
		val startsAt = schedule.scheduledAt ?: throw BadRequestException("시작 시각이 있는 일정만 내보낼 수 있습니다.")
		val timezone = schedule.timezone ?: throw BadRequestException("일정 시간대를 지정해 주세요.")
		val endsAt = schedule.endsAt ?: startsAt.plusSeconds(3600)
		val title = "${application.company} · ${schedule.action}".take(200)
		val description = listOf(
			"${application.position} 지원 일정",
			schedule.description,
		).filter(String::isNotBlank).joinToString("\n").take(4000)
		val hash = RequestFingerprint.of(
			"CALENDAR_PREVIEW", schedule.id, schedule.version, connectionId,
			title, startsAt, endsAt, timezone, schedule.location, description,
		)
		return CalendarSnapshot(hash, title, startsAt, endsAt, description)
	}
}

data class CalendarSnapshot(
	val previewHash: String,
	val title: String,
	val startsAt: Instant,
	val endsAt: Instant,
	val description: String,
)

sealed interface CalendarClaimResult {
	data class AlreadyConfirmed(val response: CalendarExportResponse) : CalendarClaimResult
	data class Claimed(
		val claimToken: UUID,
		val connectionId: UUID,
		val event: GoogleCalendarEvent,
	) : CalendarClaimResult
}

internal fun calendarClaimDuration(connectTimeout: Duration, readTimeout: Duration): Duration =
	connectTimeout.plus(readTimeout).multipliedBy(2).plusSeconds(10)

private fun deterministicProviderEventId(export: CalendarExport): String =
	"jobvis" + RequestFingerprint.of(
		"CALENDAR_PROVIDER_EVENT", export.scheduleId, export.scheduleVersion, export.previewHash,
	).take(50)

private fun CalendarExport.toResponse(): CalendarExportResponse = CalendarExportResponse(
	id = id,
	scheduleId = scheduleId,
	connectionId = connectionId,
	scheduleVersion = scheduleVersion,
	previewHash = previewHash,
	title = title,
	startsAt = startsAt,
	endsAt = endsAt,
	timezone = timezone,
	location = location,
	description = description,
	status = status.name.lowercase(),
	providerEventId = providerEventId,
	errorCode = lastErrorCode,
	confirmedAt = confirmedAt,
	createdAt = createdAt,
)
