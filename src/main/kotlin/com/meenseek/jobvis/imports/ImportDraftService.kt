package com.meenseek.jobvis.imports

import com.meenseek.jobvis.application.ActivityType
import com.meenseek.jobvis.application.ApplicationActivity
import com.meenseek.jobvis.application.ApplicationActivityRepository
import com.meenseek.jobvis.application.ApplicationAssembler
import com.meenseek.jobvis.application.ApplicationChange
import com.meenseek.jobvis.application.ApplicationChangeRepository
import com.meenseek.jobvis.application.ApplicationEmail
import com.meenseek.jobvis.application.ApplicationEmailRepository
import com.meenseek.jobvis.application.ApplicationMutationRepository
import com.meenseek.jobvis.application.ApplicationResponse
import com.meenseek.jobvis.application.ApplicationResult
import com.meenseek.jobvis.application.ApplicationSchedule
import com.meenseek.jobvis.application.ApplicationScheduleRepository
import com.meenseek.jobvis.application.ApplicationStage
import com.meenseek.jobvis.application.compact
import com.meenseek.jobvis.application.JobApplication
import com.meenseek.jobvis.application.JobApplicationRepository
import com.meenseek.jobvis.application.RequestFingerprint
import com.meenseek.jobvis.application.ScheduleType
import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ConflictException
import com.meenseek.jobvis.common.NotFoundException
import com.meenseek.jobvis.common.BusinessTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.data.domain.PageRequest
import java.util.UUID
import java.math.BigDecimal
import tools.jackson.databind.ObjectMapper

@Service
class ImportDraftService(
	private val draftRepository: ImportDraftRepository,
	private val applicationRepository: JobApplicationRepository,
	private val scheduleRepository: ApplicationScheduleRepository,
	private val emailRepository: ApplicationEmailRepository,
	private val activityRepository: ApplicationActivityRepository,
	private val changeRepository: ApplicationChangeRepository,
	private val mutationRepository: ApplicationMutationRepository,
	private val jdbcTemplate: JdbcTemplate,
	private val assembler: ApplicationAssembler,
	private val objectMapper: ObjectMapper,
	private val clock: Clock,
) {
	@Transactional(readOnly = true)
	fun list(
		userId: UUID,
		statusValue: String?,
		page: Int,
		size: Int,
	): ImportPageResponse<ImportDraftResponse> {
		if (page < 0 || size !in 1..100) throw BadRequestException("page는 0 이상, size는 1~100이어야 합니다.")
		val status = statusValue?.takeUnless(String::isBlank)?.let(::parseImportDraftStatus)
		val slice = draftRepository.findAllForUser(userId, status, PageRequest.of(page, size))
		return ImportPageResponse(slice.content.map(ImportDraft::toResponse), page, size, slice.hasNext())
	}

	@Transactional(readOnly = true)
	fun get(userId: UUID, draftId: UUID): ImportDraftResponse = findOwned(userId, draftId).toResponse()

	@Transactional
	fun update(userId: UUID, draftId: UUID, request: UpdateImportDraftRequest): ImportDraftResponse {
		val draft = findOwnedLocked(userId, draftId)
		verifyVersion(draft, request.expectedVersion)
		if (draft.status != ImportDraftStatus.PENDING) {
			throw ConflictException("대기 중인 초안만 수정할 수 있습니다.")
		}
		val values = validateAndNormalize(request)
		draft.update(
			values.company, values.position, values.location, values.employmentType, values.appliedAt,
			values.stage, values.highestStageReached, values.screeningPassed, values.result,
			values.scheduleType, values.scheduleAction, values.scheduledAt, values.scheduleEndsAt,
			Instant.now(clock),
		)
		return draftRepository.saveAndFlush(draft).toResponse()
	}

	@Transactional
	fun accept(userId: UUID, draftId: UUID, request: AcceptImportDraftRequest): ApplicationResponse {
		val now = Instant.now(clock)
		val operation = "IMPORT_ACCEPT"
		validateAcceptTarget(request)
		val fingerprint = RequestFingerprint.of(
			operation,
			draftId,
			request.expectedVersion,
			request.targetApplicationId?.toString().orEmpty(),
			request.expectedApplicationVersion?.toString().orEmpty(),
			request.expectedScheduleVersion?.toString().orEmpty(),
		)
		mutationRepository.reserve(UUID.randomUUID(), userId, request.mutationId, operation, fingerprint, now)
		val mutation = mutationRepository.findLocked(userId, request.mutationId)
			?: throw IllegalStateException("예약한 mutation을 찾을 수 없습니다.")
		if (!mutation.matchesRequest(operation, fingerprint)) throw mutationConflict()
		if (mutation.completed) {
			val payload = mutation.resultPayload ?: throw IllegalStateException("완료된 mutation 응답이 없습니다.")
			val watermark = mutation.historyWatermark
				?: throw IllegalStateException("완료된 mutation 이력 기준점이 없습니다.")
			return assembler.restore(
				userId, objectMapper.readValue(payload, ApplicationResponse::class.java), watermark,
			)
		}

		val draft = findOwnedLocked(userId, draftId)
		verifyVersion(draft, request.expectedVersion)
		if (draft.status != ImportDraftStatus.PENDING) {
			throw ConflictException("대기 중인 초안만 수락할 수 있습니다.")
		}
		validateProgress(
			draft.stage, draft.highestStageReached, draft.screeningPassed, draft.result,
			draft.scheduleType, draft.scheduleAction, draft.scheduledAt, draft.scheduleEndsAt,
		)
		val application = request.targetApplicationId?.let { applicationId ->
			attachToApplication(userId, applicationId, request, draft, now)
		} ?: createApplication(userId, request, draft, now)
		val applicationId = application.id
		emailRepository.save(
			ApplicationEmail.create(
				UUID.randomUUID(), userId, applicationId, draft.connectionId, draft.provider.apiValue(),
				draft.providerMessageId, draft.subject, draft.sender, draft.receivedAt,
				draft.sourceSummary, now,
			),
		)
		activityRepository.save(
			ApplicationActivity.create(
				UUID.randomUUID(), userId, applicationId, ActivityType.EMAIL,
				if (request.targetApplicationId == null) "채용 메일 초안을 수락했습니다" else "후속 채용 메일을 연결했습니다",
					"메일 원문과 첨부파일은 저장하지 않고 확인한 추출 정보만 반영했습니다.",
					draft.receivedAt, now,
			),
		)
		draft.accept(applicationId, request.mutationId, fingerprint, now)
		updateLedger(draft, "ACCEPTED", now)
		draftRepository.save(draft)
		emailRepository.flush()
		activityRepository.flush()
		changeRepository.flush()
		val watermark = mutationRepository.historyWatermark(userId, applicationId)
		val response = assembler.assembleAt(userId, application, watermark)
		mutation.complete(
			applicationId, application.version, watermark, objectMapper.writeValueAsString(response.compact()), now,
		)
		mutationRepository.save(mutation)
		return response
	}

	private fun createApplication(
		userId: UUID,
		request: AcceptImportDraftRequest,
		draft: ImportDraft,
		now: Instant,
	): JobApplication {
		val applicationId = UUID.randomUUID()
		val application = JobApplication.createImported(
			applicationId, userId, draft.company, draft.position, draft.location, draft.employmentType,
				draft.appliedAt, draft.stage, draft.highestStageReached, draft.screeningPassed, draft.result,
				draft.confidence < REVIEW_CONFIDENCE_THRESHOLD,
				"${draft.provider.apiValue()} 메일", request.mutationId, now,
		)
		applicationRepository.saveAndFlush(application)
		val schedule = importedOrDefaultSchedule(userId, applicationId, draft, now)
		scheduleRepository.save(schedule)
		return application
	}

	private fun attachToApplication(
		userId: UUID,
		applicationId: UUID,
		request: AcceptImportDraftRequest,
		draft: ImportDraft,
		now: Instant,
	): JobApplication {
		val application = applicationRepository.findOwnedLocked(applicationId, userId)
			?: throw NotFoundException("지원 정보를 찾을 수 없습니다.")
		if (application.version != request.expectedApplicationVersion) {
			throw ConflictException("지원 정보가 다른 곳에서 변경되었습니다. 최신 내용을 확인해 주세요.")
		}
		val schedule = scheduleRepository.findForApplicationLocked(userId, applicationId)
			?: throw NotFoundException("지원 일정 정보를 찾을 수 없습니다.")
		if (schedule.version != request.expectedScheduleVersion) {
			throw ConflictException("일정이 다른 곳에서 변경되었습니다. 최신 내용을 확인해 주세요.")
		}

		val previous = ProgressSnapshot.of(application)
		application.applyImportedProgress(
			draft.stage, draft.highestStageReached, draft.screeningPassed, draft.result,
			draft.confidence < REVIEW_CONFIDENCE_THRESHOLD, now,
		)
		application.markMutation(request.mutationId, now)
		val next = ProgressSnapshot.of(application)
		val progressChanges = listOf(
			ChangeValue("stage", "현재 단계", previous.stage, next.stage),
			ChangeValue("highestStageReached", "최고 도달 단계", previous.highestStage, next.highestStage),
			ChangeValue("screeningPassed", "서류 통과 여부", previous.screeningPassed, next.screeningPassed),
			ChangeValue("result", "지원 결과", previous.result, next.result),
			ChangeValue("needsReview", "검토 상태", previous.needsReview, next.needsReview),
			ChangeValue("status", "진행 상태", previous.statusLabel, next.statusLabel),
		)
		progressChanges.forEach { change ->
			saveChange(
				userId, applicationId, request.mutationId, change.key, change.title,
				change.before, change.after, now,
			)
		}
		if (progressChanges.any { it.before != it.after }) {
			activityRepository.save(
				ApplicationActivity.create(
					UUID.randomUUID(), userId, applicationId, ActivityType.STATUS,
					"${next.statusLabel} 상태를 반영했습니다", "연결한 채용 메일의 상세 진행 상황을 반영했습니다.", now,
				),
			)
		}

		if (draft.scheduleType != null && draft.scheduleAction != null && draft.scheduledAt != null) {
			val previous = ScheduleSnapshot.of(schedule)
			if (schedule.mergeImported(
					draft.scheduleType!!, draft.scheduleAction!!, draft.scheduledAt!!, draft.scheduleEndsAt,
					draft.receivedAt, now,
				)
			) {
				saveScheduleChanges(userId, applicationId, request.mutationId, previous, schedule, now)
				scheduleRepository.saveAndFlush(schedule)
			}
		}
		return applicationRepository.saveAndFlush(application)
	}

	private fun importedOrDefaultSchedule(
		userId: UUID,
		applicationId: UUID,
		draft: ImportDraft,
		now: Instant,
	): ApplicationSchedule = if (
		draft.scheduleType != null && draft.scheduleAction != null && draft.scheduledAt != null
	) {
		ApplicationSchedule.createImported(
			UUID.randomUUID(), userId, applicationId, draft.scheduleType!!, draft.scheduleAction!!,
			draft.scheduledAt!!, draft.scheduleEndsAt, draft.receivedAt, now,
		)
	} else {
		ApplicationSchedule.createDefault(UUID.randomUUID(), userId, applicationId, now)
	}

	private fun validateAcceptTarget(request: AcceptImportDraftRequest) {
		val hasTarget = request.targetApplicationId != null
		if (hasTarget != (request.expectedApplicationVersion != null) ||
			hasTarget != (request.expectedScheduleVersion != null)
		) {
			throw BadRequestException("기존 지원에 연결하려면 지원과 일정의 현재 버전을 함께 입력해 주세요.")
		}
	}

	private fun saveScheduleChanges(
		userId: UUID,
		applicationId: UUID,
		mutationId: UUID,
		before: ScheduleSnapshot,
		after: ApplicationSchedule,
		now: Instant,
	) {
		val values = listOf(
			ChangeValue("scheduleType", "일정 종류", before.type, after.scheduleType.apiValue()),
			ChangeValue("scheduleAction", "일정 할 일", before.action, after.action),
			ChangeValue("scheduledAt", "일정 시작", before.startsAt, after.scheduledAt?.toString().orEmpty()),
			ChangeValue("scheduleEndsAt", "일정 종료", before.endsAt, after.endsAt?.toString().orEmpty()),
			ChangeValue("scheduleTimezone", "일정 시간대", before.timezone, after.timezone),
			ChangeValue("scheduleLocation", "일정 장소", before.location, after.location),
			ChangeValue("scheduleDescription", "일정 설명", before.description, after.description),
			ChangeValue("scheduleCompleted", "일정 상태", before.completed, if (after.completed) "완료" else "미완료"),
		)
		values.forEach { value ->
			saveChange(userId, applicationId, mutationId, value.key, value.title, value.before, value.after, now)
		}
	}

	private fun saveChange(
		userId: UUID,
		applicationId: UUID,
		mutationId: UUID,
		fieldKey: String,
		title: String,
		beforeValue: String,
		afterValue: String,
		now: Instant,
	) {
		if (beforeValue == afterValue) return
		changeRepository.save(
			ApplicationChange.create(
				UUID.randomUUID(), userId, applicationId, mutationId,
				fieldKey, title, beforeValue, afterValue, now,
			),
		)
	}

	@Transactional
	fun reject(userId: UUID, draftId: UUID, request: DecideImportDraftRequest): ImportDraftResponse {
		val fingerprint = RequestFingerprint.of("IMPORT_REJECT", draftId, request.expectedVersion)
		draftRepository.findByDecisionMutation(userId, request.mutationId)?.let { decided ->
			if (decided.id == draftId && decided.status == ImportDraftStatus.REJECTED &&
				decided.decisionFingerprint == fingerprint
			) return decided.toResponse()
			throw mutationConflict()
		}
		val draft = findOwnedLocked(userId, draftId)
		if (draft.status == ImportDraftStatus.REJECTED && draft.decisionMutationId == request.mutationId) {
			if (draft.decisionFingerprint == fingerprint) return draft.toResponse()
			throw mutationConflict()
		}
		verifyVersion(draft, request.expectedVersion)
		if (draft.status != ImportDraftStatus.PENDING) {
			throw ConflictException("대기 중인 초안만 제외할 수 있습니다.")
		}
		val now = Instant.now(clock)
		draft.reject(request.mutationId, fingerprint, now)
		updateLedger(draft, "REJECTED", now)
		return draftRepository.saveAndFlush(draft).toResponse()
	}

	private fun validateAndNormalize(request: UpdateImportDraftRequest): DraftValues {
		val company = request.company.requiredTrimmed("회사명을 입력해 주세요.")
		val position = request.position.requiredTrimmed("포지션을 입력해 주세요.")
		val location = request.location.requiredTrimmed("근무지를 입력해 주세요.")
		val employmentType = request.employmentType.requiredTrimmed("고용 형태를 입력해 주세요.")
		val stage = ApplicationStage.fromApiValue(request.stage)
		val highestStage = ApplicationStage.fromApiValue(request.highestStageReached)
		val result = ApplicationResult.fromApiValue(request.result)
		val scheduleType = request.scheduleType?.takeUnless(String::isBlank)?.let(ScheduleType::fromApiValue)
		val scheduleAction = request.scheduleAction?.trim()?.takeIf(String::isNotEmpty)
		validateProgress(
			stage, highestStage, request.screeningPassed, result,
			scheduleType, scheduleAction, request.scheduledAt, request.scheduleEndsAt,
		)
		val today = BusinessTime.today(clock)
		if (request.appliedAt.isAfter(today)) throw BadRequestException("지원일은 미래일 수 없습니다.")
		return DraftValues(
			company, position, location, employmentType, request.appliedAt, stage, highestStage,
			request.screeningPassed, result, scheduleType, scheduleAction,
			request.scheduledAt, request.scheduleEndsAt,
		)
	}

	private fun validateProgress(
		stage: ApplicationStage,
		highestStage: ApplicationStage,
		screeningPassed: Boolean,
		result: ApplicationResult,
		scheduleType: ScheduleType?,
		scheduleAction: String?,
		scheduledAt: Instant?,
		scheduleEndsAt: Instant?,
	) {
		if (highestStage.highest(stage) != highestStage) {
			throw BadRequestException("최고 도달 단계는 현재 단계보다 앞설 수 없습니다.")
		}
		if (highestStage.passedScreeningByProgress() && !screeningPassed) {
			throw BadRequestException("면접 이상 단계에서는 서류 통과 여부가 참이어야 합니다.")
		}
		if (result == ApplicationResult.OFFERED && (
				stage != ApplicationStage.OFFER || highestStage != ApplicationStage.OFFER || !screeningPassed
			)) {
			throw BadRequestException("최종 합격 결과는 오퍼 단계와 일치해야 합니다.")
		}
		val scheduleFields = listOf(scheduleType, scheduleAction, scheduledAt)
		if (scheduleFields.any { it != null } && scheduleFields.any { it == null }) {
			throw BadRequestException("일정 종류, 할 일, 시작 시각은 함께 입력해 주세요.")
		}
		if (scheduleEndsAt != null && scheduledAt == null) {
			throw BadRequestException("일정 종료 시각만 입력할 수 없습니다.")
		}
		if (scheduledAt != null && scheduleEndsAt != null && scheduleEndsAt.isBefore(scheduledAt)) {
			throw BadRequestException("일정 종료 시각은 시작 시각보다 빠를 수 없습니다.")
		}
	}

	private fun findOwned(userId: UUID, draftId: UUID): ImportDraft =
		draftRepository.findOwned(draftId, userId) ?: throw NotFoundException("가져오기 초안을 찾을 수 없습니다.")

	private fun findOwnedLocked(userId: UUID, draftId: UUID): ImportDraft =
		draftRepository.findOwnedLocked(draftId, userId) ?: throw NotFoundException("가져오기 초안을 찾을 수 없습니다.")

	private fun findOwnedApplication(userId: UUID, applicationId: UUID): JobApplication =
		applicationRepository.findOwned(applicationId, userId) ?: throw NotFoundException("지원 정보를 찾을 수 없습니다.")

	private fun verifyVersion(draft: ImportDraft, expectedVersion: Long) {
		if (draft.version != expectedVersion) {
			throw ConflictException("초안이 다른 곳에서 변경되었습니다. 최신 내용을 확인해 주세요.")
		}
	}

	private fun String.requiredTrimmed(message: String): String =
		trim().takeIf(String::isNotEmpty) ?: throw BadRequestException(message)

	private fun mutationConflict(): ConflictException = ConflictException("이미 다른 결정에 사용된 mutationId입니다.")

	private fun updateLedger(draft: ImportDraft, state: String, now: Instant) {
		jdbcTemplate.update(
			"""
				UPDATE mail_ingestion_ledger
				SET state = ?, updated_at = ?
				WHERE user_id = ? AND connection_id = ? AND provider_message_id = ?
			""".trimIndent(),
			state, java.sql.Timestamp.from(now), draft.userId, draft.connectionId, draft.providerMessageId,
		)
	}

	private data class DraftValues(
		val company: String,
		val position: String,
		val location: String,
		val employmentType: String,
		val appliedAt: LocalDate,
		val stage: ApplicationStage,
		val highestStageReached: ApplicationStage,
		val screeningPassed: Boolean,
		val result: ApplicationResult,
		val scheduleType: ScheduleType?,
		val scheduleAction: String?,
		val scheduledAt: Instant?,
		val scheduleEndsAt: Instant?,
	)

	private data class ChangeValue(
		val key: String,
		val title: String,
		val before: String,
		val after: String,
	)

	private data class ScheduleSnapshot(
		val type: String,
		val action: String,
		val startsAt: String,
		val endsAt: String,
		val timezone: String,
		val location: String,
		val description: String,
		val completed: String,
	) {
		companion object {
			fun of(schedule: ApplicationSchedule): ScheduleSnapshot = ScheduleSnapshot(
				schedule.scheduleType.apiValue(), schedule.action, schedule.scheduledAt?.toString().orEmpty(),
				schedule.endsAt?.toString().orEmpty(), schedule.timezone, schedule.location, schedule.description,
				if (schedule.completed) "완료" else "미완료",
			)
		}
	}

	private data class ProgressSnapshot(
		val stage: String,
		val highestStage: String,
		val screeningPassed: String,
		val result: String,
		val needsReview: String,
		val statusLabel: String,
	) {
		companion object {
			fun of(application: JobApplication): ProgressSnapshot = ProgressSnapshot(
				application.stage.apiValue(), application.highestStageReached.apiValue(),
				if (application.screeningPassed) "통과" else "미통과",
				application.result.apiValue(),
				if (application.needsReview) "확인 필요" else "확인 완료",
				application.currentStatusLabel(),
			)
		}
	}

	private companion object {
		val REVIEW_CONFIDENCE_THRESHOLD: BigDecimal = BigDecimal("0.800")
	}
}

private fun ImportDraft.toResponse(): ImportDraftResponse = ImportDraftResponse(
	id = id,
	runId = runId,
	connectionId = connectionId,
	provider = provider.apiValue(),
	providerMessageId = providerMessageId,
	subject = subject,
	sender = sender,
	receivedAt = receivedAt,
	sourceSummary = sourceSummary,
	company = company,
	position = position,
	location = location,
	employmentType = employmentType,
	appliedAt = appliedAt,
	stage = stage.apiValue(),
	highestStageReached = highestStageReached.apiValue(),
	screeningPassed = screeningPassed,
	result = result.apiValue(),
	scheduleType = scheduleType?.apiValue(),
	scheduleAction = scheduleAction,
	scheduledAt = scheduledAt,
	scheduleEndsAt = scheduleEndsAt,
	confidence = confidence,
	status = status.name.lowercase(),
	acceptedApplicationId = acceptedApplicationId,
	version = version,
	decidedAt = decidedAt,
)

private fun parseImportDraftStatus(value: String): ImportDraftStatus =
	runCatching { ImportDraftStatus.valueOf(value.trim().uppercase()) }
		.getOrElse { throw BadRequestException("초안 상태 필터가 올바르지 않습니다.") }
