package com.meenseek.jobvis.application

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ConflictException
import com.meenseek.jobvis.common.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import org.springframework.data.domain.PageRequest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.DateTimeException
import java.util.Locale
import java.util.UUID
import tools.jackson.databind.ObjectMapper

@Service
class ApplicationService(
	private val applicationRepository: JobApplicationRepository,
	private val scheduleRepository: ApplicationScheduleRepository,
	private val activityRepository: ApplicationActivityRepository,
	private val changeRepository: ApplicationChangeRepository,
	private val mutationRepository: ApplicationMutationRepository,
	private val assembler: ApplicationAssembler,
	private val objectMapper: ObjectMapper,
	private val clock: Clock,
) {
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	fun list(userId: UUID, query: String?, status: String?): LegacyApplicationListResult {
		val normalizedQuery = query.orEmpty().trim().lowercase(Locale.ROOT)
		val normalizedStatus = normalizeStatus(status)
		val slice = applicationRepository
			.findListItems(userId, normalizedQuery, normalizedStatus, PageRequest.of(0, LEGACY_LIST_LIMIT))
		return LegacyApplicationListResult(assembler.assembleListItems(userId, slice.content), slice.hasNext())
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	fun listPage(
		userId: UUID,
		query: String?,
		status: String?,
		page: Int,
		limit: Int,
	): ApplicationListPageResponse {
		if (page < 0 || limit !in 1..100) throw BadRequestException("page는 0 이상, limit은 1~100이어야 합니다.")
		val normalizedQuery = query.orEmpty().trim().lowercase(Locale.ROOT)
		val normalizedStatus = normalizeStatus(status)
		val slice = applicationRepository.findListItems(
			userId, normalizedQuery, normalizedStatus, PageRequest.of(page, limit),
		)
		return ApplicationListPageResponse(
			items = assembler.assembleListItems(userId, slice.content),
			page = page,
			limit = limit,
			hasNext = slice.hasNext(),
		)
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	fun get(userId: UUID, applicationId: UUID): ApplicationResponse =
		assembler.assemble(userId, findOwnedApplication(userId, applicationId))

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	fun getSchedule(userId: UUID, applicationId: UUID): ApplicationScheduleResponse {
		val application = findOwnedApplication(userId, applicationId)
		return findSchedule(userId, applicationId).toResponse(application.version)
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	fun emails(userId: UUID, applicationId: UUID, before: Long?, limit: Int): HistoryPageResponse<EmailResponse> {
		validateHistoryRequest(limit)
		findOwnedApplication(userId, applicationId)
		return assembler.emailsPage(userId, applicationId, before ?: Long.MAX_VALUE, limit)
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	fun activities(
		userId: UUID,
		applicationId: UUID,
		before: Long?,
		limit: Int,
	): HistoryPageResponse<ActivityResponse> {
		validateHistoryRequest(limit)
		findOwnedApplication(userId, applicationId)
		return assembler.activitiesPage(userId, applicationId, before ?: Long.MAX_VALUE, limit)
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	fun changes(userId: UUID, applicationId: UUID, before: Long?, limit: Int): HistoryPageResponse<ChangeResponse> {
		validateHistoryRequest(limit)
		findOwnedApplication(userId, applicationId)
		return assembler.changesPage(userId, applicationId, before ?: Long.MAX_VALUE, limit)
	}

	@Transactional
	fun create(userId: UUID, request: CreateRequest): ApplicationResponse {
		val company = request.company.requiredTrimmed("회사명을 입력해 주세요.")
		val position = request.position.requiredTrimmed("포지션을 입력해 주세요.")
		val stage = ApplicationStage.fromApiValue(request.stage)
		val operation = "CREATE"
		val fingerprint = RequestFingerprint.of(operation, company, position, stage)
		val now = Instant.now(clock)
		val mutation = reserveMutation(userId, request.mutationId, operation, fingerprint, now)
		if (mutation.completed) {
			return restoreApplicationMutation(userId, mutation)
		}

		val applicationId = UUID.randomUUID()
		val application = JobApplication.create(
			id = applicationId,
			userId = userId,
			company = company,
			position = position,
			stage = stage,
			creationMutationId = request.mutationId,
			appliedAt = LocalDate.ofInstant(now, SEOUL),
			now = now,
		)
		applicationRepository.saveAndFlush(application)
		scheduleRepository.save(ApplicationSchedule.createDefault(UUID.randomUUID(), userId, applicationId, now))
		activityRepository.save(
			ApplicationActivity.create(
				id = UUID.randomUUID(),
				userId = userId,
				applicationId = applicationId,
				activityType = ActivityType.STATUS,
				title = "지원 이력을 추가했습니다",
				description = "메일 원문 없이 직접 추가했습니다.",
				occurredAt = now,
			),
		)
		return completeMutation(userId, mutation, application, now)
	}

	@Transactional
	fun updateDetails(userId: UUID, applicationId: UUID, request: UpdateDetailsRequest): ApplicationResponse {
		val company = request.company.requiredTrimmed("회사명을 입력해 주세요.")
		val position = request.position.requiredTrimmed("포지션을 입력해 주세요.")
		val location = request.location.defaultIfBlank("근무지 미입력")
		val employmentType = request.employmentType.defaultIfBlank("고용 형태 미입력")
		val operation = "UPDATE_DETAILS"
		val fingerprint = RequestFingerprint.of(
			operation,
			request.expectedVersion,
			company,
			position,
			location,
			employmentType,
		)
		val now = Instant.now(clock)
		val mutation = reserveMutation(userId, request.mutationId, operation, fingerprint, now)
		replayIfCompleted(userId, applicationId, mutation)?.let { return it }

		val application = findOwnedApplicationLocked(userId, applicationId)
		verifyVersion(application, request.expectedVersion)
		var changed = false
		changed = changed or saveChangeIfDifferent(
			userId, applicationId, request.mutationId, "company", "회사", application.company, company, now,
		)
		changed = changed or saveChangeIfDifferent(
			userId, applicationId, request.mutationId, "position", "포지션", application.position, position, now,
		)
		changed = changed or saveChangeIfDifferent(
			userId, applicationId, request.mutationId, "location", "근무지", application.location, location, now,
		)
		changed = changed or saveChangeIfDifferent(
			userId,
			applicationId,
			request.mutationId,
			"employmentType",
			"고용 형태",
			application.employmentType,
			employmentType,
			now,
		)
		if (changed) {
			application.updateDetails(company, position, location, employmentType, now)
			application.markMutation(request.mutationId, now)
			applicationRepository.saveAndFlush(application)
		}
		return completeMutation(userId, mutation, application, now)
	}

	@Transactional
	fun updateMemo(userId: UUID, applicationId: UUID, request: UpdateMemoRequest): ApplicationResponse {
		val memo = request.memo.trim()
		val operation = "UPDATE_MEMO"
		val fingerprint = RequestFingerprint.of(operation, request.expectedVersion, memo)
		val now = Instant.now(clock)
		val mutation = reserveMutation(userId, request.mutationId, operation, fingerprint, now)
		replayIfCompleted(userId, applicationId, mutation)?.let { return it }

		val application = findOwnedApplicationLocked(userId, applicationId)
		verifyVersion(application, request.expectedVersion)
		val changed = saveChangeIfDifferent(
			userId = userId,
			applicationId = applicationId,
			mutationId = request.mutationId,
			fieldKey = "memo",
			title = "메모",
			beforeValue = application.memo.formatEmpty(),
			afterValue = memo.formatEmpty(),
			now = now,
		)
		if (changed) {
			application.updateMemo(memo, now)
			application.markMutation(request.mutationId, now)
			applicationRepository.saveAndFlush(application)
		}
		return completeMutation(userId, mutation, application, now)
	}

	@Transactional
	fun updateStatus(userId: UUID, applicationId: UUID, request: UpdateStatusRequest): ApplicationResponse {
		val nextStatus = normalizeCommandStatus(request.status)
		val operation = "UPDATE_STATUS"
		val fingerprint = RequestFingerprint.of(operation, request.expectedVersion, nextStatus)
		val now = Instant.now(clock)
		val mutation = reserveMutation(userId, request.mutationId, operation, fingerprint, now)
		replayIfCompleted(userId, applicationId, mutation)?.let { return it }

		val application = findOwnedApplicationLocked(userId, applicationId)
		verifyVersion(application, request.expectedVersion)
		val previousStatus = application.currentStatusValue()
		val previousLabel = application.currentStatusLabel()
		val neededReview = application.needsReview
		if (previousStatus != nextStatus) {
			applyStatusTransition(application, nextStatus, now)
			application.markMutation(request.mutationId, now)
			val nextLabel = application.currentStatusLabel()
			applicationRepository.saveAndFlush(application)
			activityRepository.save(
				ApplicationActivity.create(
					id = UUID.randomUUID(),
					userId = userId,
					applicationId = applicationId,
					activityType = ActivityType.STATUS,
					title = "$nextLabel 상태가 되었습니다",
					description = "현재 지원 진행 상황에 반영했습니다.",
					occurredAt = now,
				),
			)
			changeRepository.save(
				ApplicationChange.create(
					UUID.randomUUID(), userId, applicationId, request.mutationId,
					"status", "진행 상태", previousLabel, nextLabel, now,
				),
			)
			if (neededReview) {
				changeRepository.save(
					ApplicationChange.create(
						UUID.randomUUID(), userId, applicationId, request.mutationId,
						"needsReview", "검토 상태", "확인 필요", "확인 완료", now,
					),
				)
			}
		}
		return completeMutation(userId, mutation, application, now)
	}

	@Transactional
	fun completeSchedule(userId: UUID, applicationId: UUID, request: MutationRequest): ApplicationResponse {
		val operation = "COMPLETE_SCHEDULE"
		val fingerprint = RequestFingerprint.of(operation, request.expectedVersion)
		val now = Instant.now(clock)
		val mutation = reserveMutation(userId, request.mutationId, operation, fingerprint, now)
		replayIfCompleted(userId, applicationId, mutation)?.let { return it }

		val application = findOwnedApplicationLocked(userId, applicationId)
		verifyVersion(application, request.expectedVersion)
		val schedule = scheduleRepository.findForApplicationLocked(userId, applicationId)
			?: throw NotFoundException("지원 일정 정보를 찾을 수 없습니다.")
		if (!schedule.completed) {
			schedule.complete(now)
			application.markMutation(request.mutationId, now)
			applicationRepository.saveAndFlush(application)
			scheduleRepository.save(schedule)
			activityRepository.save(
				ApplicationActivity.create(
					id = UUID.randomUUID(),
					userId = userId,
					applicationId = applicationId,
					activityType = ActivityType.TASK,
					title = "${schedule.action} 완료",
					description = "예정된 일정을 완료했습니다.",
					occurredAt = now,
				),
			)
			changeRepository.save(
				ApplicationChange.create(
					UUID.randomUUID(), userId, applicationId, request.mutationId,
					"scheduleCompleted", "일정 상태", "미완료", "완료", now,
				),
			)
		}
		return completeMutation(userId, mutation, application, now)
	}

	@Transactional
	fun updateSchedule(
		userId: UUID,
		applicationId: UUID,
		request: UpdateScheduleRequest,
	): ApplicationScheduleResponse {
		val type = ScheduleType.fromApiValue(request.scheduleType)
		val action = request.action.requiredTrimmed("일정의 할 일을 입력해 주세요.")
		val timezone = request.timezone.requiredTrimmed("일정 시간대를 입력해 주세요.")
		try {
			ZoneId.of(timezone)
		} catch (_: DateTimeException) {
			throw BadRequestException("일정 시간대가 올바르지 않습니다.")
		}
		if (request.endsAt != null && request.endsAt.isBefore(request.scheduledAt)) {
			throw BadRequestException("일정 종료 시각은 시작 시각보다 빠를 수 없습니다.")
		}
		val location = request.location.trim()
		val description = request.description.trim()
		val operation = "UPDATE_SCHEDULE"
		val fingerprint = RequestFingerprint.of(
			operation, applicationId, request.expectedVersion, request.expectedScheduleVersion, type, action,
			request.scheduledAt, request.endsAt?.toString().orEmpty(), timezone, location, description,
		)
		val now = Instant.now(clock)
		val mutation = reserveMutation(userId, request.mutationId, operation, fingerprint, now)
		if (mutation.completed) {
			if (mutation.applicationId != applicationId) throw mutationConflict()
			val payload = mutation.resultPayload ?: throw IllegalStateException("완료된 mutation 응답이 없습니다.")
			return objectMapper.readValue(payload, ApplicationScheduleResponse::class.java)
		}
		val application = applicationRepository.findOwnedLocked(applicationId, userId)
			?: throw NotFoundException("지원 정보를 찾을 수 없습니다.")
		verifyVersion(application, request.expectedVersion)
		val schedule = scheduleRepository.findForApplicationLocked(userId, applicationId)
			?: throw NotFoundException("지원 일정 정보를 찾을 수 없습니다.")
		if (schedule.version != request.expectedScheduleVersion) {
			throw ConflictException("일정이 다른 곳에서 변경되었습니다. 최신 내용을 확인해 주세요.")
		}
		val previous = ScheduleValues.from(schedule)
		val changed = schedule.update(
			type, action, request.scheduledAt, request.endsAt, timezone, location, description, now,
		)
		if (changed) {
			saveScheduleChanges(userId, applicationId, request.mutationId, previous, schedule, now)
			application.markMutation(request.mutationId, now)
			applicationRepository.saveAndFlush(application)
			scheduleRepository.saveAndFlush(schedule)
		}
		val response = schedule.toResponse(application.version)
		val watermark = mutationRepository.historyWatermark(userId, application.id)
		mutation.complete(application.id, application.version, watermark, objectMapper.writeValueAsString(response), now)
		mutationRepository.save(mutation)
		return response
	}

	@Transactional
	fun completeReview(userId: UUID, applicationId: UUID, request: MutationRequest): ApplicationResponse {
		val operation = "COMPLETE_REVIEW"
		val fingerprint = RequestFingerprint.of(operation, request.expectedVersion)
		val now = Instant.now(clock)
		val mutation = reserveMutation(userId, request.mutationId, operation, fingerprint, now)
		replayIfCompleted(userId, applicationId, mutation)?.let { return it }

		val application = findOwnedApplicationLocked(userId, applicationId)
		verifyVersion(application, request.expectedVersion)
		if (application.needsReview) {
			application.completeReview(now)
			application.markMutation(request.mutationId, now)
			applicationRepository.saveAndFlush(application)
			changeRepository.save(
				ApplicationChange.create(
					UUID.randomUUID(), userId, applicationId, request.mutationId,
					"needsReview", "검토 상태", "확인 필요", "확인 완료", now,
				),
			)
		}
		return completeMutation(userId, mutation, application, now)
	}

	private fun reserveMutation(
		userId: UUID,
		mutationId: UUID,
		operation: String,
		fingerprint: String,
		now: Instant,
	): ApplicationMutation {
		mutationRepository.reserve(UUID.randomUUID(), userId, mutationId, operation, fingerprint, now)
		val mutation = mutationRepository.findLocked(userId, mutationId)
			?: throw IllegalStateException("예약한 mutation을 찾을 수 없습니다.")
		if (!mutation.matchesRequest(operation, fingerprint)) throw mutationConflict()
		return mutation
	}

	private fun replayIfCompleted(
		userId: UUID,
		applicationId: UUID,
		mutation: ApplicationMutation,
	): ApplicationResponse? {
		if (!mutation.completed) return null
		if (mutation.applicationId != applicationId) throw mutationConflict()
		return restoreApplicationMutation(userId, mutation)
	}

	private fun completeMutation(
		userId: UUID,
		mutation: ApplicationMutation,
		application: JobApplication,
		now: Instant,
	): ApplicationResponse {
		activityRepository.flush()
		changeRepository.flush()
		val watermark = mutationRepository.historyWatermark(userId, application.id)
		val response = assembler.assembleAt(userId, application, watermark)
		mutation.complete(
			application.id, application.version, watermark, objectMapper.writeValueAsString(response.compact()), now,
		)
		mutationRepository.save(mutation)
		return response
	}

	private fun restoreApplicationMutation(userId: UUID, mutation: ApplicationMutation): ApplicationResponse {
		val payload = mutation.resultPayload ?: throw IllegalStateException("완료된 mutation 응답이 없습니다.")
		val watermark = mutation.historyWatermark ?: throw IllegalStateException("완료된 mutation 이력 기준점이 없습니다.")
		return assembler.restore(userId, objectMapper.readValue(payload, ApplicationResponse::class.java), watermark)
	}

	private fun verifyVersion(application: JobApplication, expectedVersion: Long) {
		if (application.version != expectedVersion) {
			throw ConflictException("지원 정보가 다른 곳에서 변경되었습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요.")
		}
	}

	private fun saveChangeIfDifferent(
		userId: UUID,
		applicationId: UUID,
		mutationId: UUID,
		fieldKey: String,
		title: String,
		beforeValue: String,
		afterValue: String,
		now: Instant,
	): Boolean {
		if (beforeValue == afterValue) return false
		changeRepository.save(
			ApplicationChange.create(
				UUID.randomUUID(), userId, applicationId, mutationId,
				fieldKey, title, beforeValue, afterValue, now,
			),
		)
		return true
	}

	private fun saveScheduleChanges(
		userId: UUID,
		applicationId: UUID,
		mutationId: UUID,
		before: ScheduleValues,
		after: ApplicationSchedule,
		now: Instant,
	) {
		listOf(
			ScheduleChange("scheduleType", "일정 종류", before.type, after.scheduleType.apiValue()),
			ScheduleChange("scheduleAction", "일정 할 일", before.action, after.action),
			ScheduleChange("scheduledAt", "일정 시작", before.startsAt, after.scheduledAt?.toString().orEmpty()),
			ScheduleChange("scheduleEndsAt", "일정 종료", before.endsAt, after.endsAt?.toString().orEmpty()),
			ScheduleChange("scheduleTimezone", "일정 시간대", before.timezone, after.timezone),
			ScheduleChange("scheduleLocation", "일정 장소", before.location, after.location),
			ScheduleChange("scheduleDescription", "일정 설명", before.description, after.description),
			ScheduleChange("scheduleCompleted", "일정 상태", before.completed, if (after.completed) "완료" else "미완료"),
		).forEach { change ->
			saveChangeIfDifferent(
				userId, applicationId, mutationId, change.key, change.title,
				change.before, change.after, now,
			)
		}
	}

	private fun applyStatusTransition(application: JobApplication, nextStatus: String, now: Instant) {
		when (nextStatus) {
			"offered" -> application.transitionToOffered(now)
			"rejected" -> application.transitionToRejected(now)
			else -> application.transitionToStage(ApplicationStage.fromApiValue(nextStatus), now)
		}
	}

	private fun findOwnedApplication(userId: UUID, applicationId: UUID): JobApplication =
		applicationRepository.findOwned(applicationId, userId)
			?: throw NotFoundException("지원 정보를 찾을 수 없습니다.")

	private fun findOwnedApplicationLocked(userId: UUID, applicationId: UUID): JobApplication =
		applicationRepository.findOwnedLocked(applicationId, userId)
			?: throw NotFoundException("지원 정보를 찾을 수 없습니다.")

	private fun validateHistoryRequest(limit: Int) {
		if (limit !in 1..100) throw BadRequestException("limit은 1~100이어야 합니다.")
	}

	private fun findSchedule(userId: UUID, applicationId: UUID): ApplicationSchedule =
		scheduleRepository.findForApplication(userId, applicationId)
			?: throw NotFoundException("지원 일정 정보를 찾을 수 없습니다.")

	private fun normalizeStatus(status: String?): String {
		val normalized = status?.takeUnless(String::isBlank)?.trim()?.lowercase(Locale.ROOT) ?: "all"
		if (normalized !in FILTERS) throw BadRequestException("지원 상태 필터가 올바르지 않습니다.")
		return normalized
	}

	private fun normalizeCommandStatus(status: String): String = normalizeStatus(status).also {
		if (it == "all" || it == "review") throw BadRequestException("변경할 지원 상태가 올바르지 않습니다.")
	}

	private fun String.requiredTrimmed(message: String): String =
		trim().takeIf(String::isNotEmpty) ?: throw BadRequestException(message)

	private fun String?.defaultIfBlank(fallback: String): String =
		this?.trim()?.takeIf(String::isNotEmpty) ?: fallback

	private fun String.formatEmpty(): String = ifBlank { "내용 없음" }

	private fun mutationConflict(): ConflictException =
		ConflictException("이미 다른 요청에 사용된 mutationId입니다.")

	private data class ScheduleChange(
		val key: String,
		val title: String,
		val before: String,
		val after: String,
	)

	private data class ScheduleValues(
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
			fun from(schedule: ApplicationSchedule): ScheduleValues = ScheduleValues(
				schedule.scheduleType.apiValue(), schedule.action, schedule.scheduledAt?.toString().orEmpty(),
				schedule.endsAt?.toString().orEmpty(), schedule.timezone, schedule.location,
				schedule.description, if (schedule.completed) "완료" else "미완료",
			)
		}
	}

	companion object {
		private const val LEGACY_LIST_LIMIT = 200
		private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
		private val FILTERS = setOf(
			"all", "review", "applied", "screening", "interview", "offer", "offered", "rejected",
		)
	}
}

data class LegacyApplicationListResult(
	val items: List<ApplicationListItemResponse>,
	val hasNext: Boolean,
)

internal fun ApplicationSchedule.toResponse(applicationVersion: Long): ApplicationScheduleResponse = ApplicationScheduleResponse(
	id = id,
	applicationId = applicationId,
	applicationVersion = applicationVersion,
	version = version,
	scheduleType = scheduleType.apiValue(),
	action = action,
	scheduledAt = scheduledAt,
	endsAt = endsAt,
	timezone = timezone,
	location = location,
	description = description,
	completed = completed,
	completedAt = completedAt,
)
