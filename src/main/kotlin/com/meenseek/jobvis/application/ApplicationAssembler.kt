package com.meenseek.jobvis.application

import com.meenseek.jobvis.common.NotFoundException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Component
class ApplicationAssembler(
	private val scheduleRepository: ApplicationScheduleRepository,
	private val emailRepository: ApplicationEmailRepository,
	private val activityRepository: ApplicationActivityRepository,
	private val changeRepository: ApplicationChangeRepository,
	private val mutationRepository: ApplicationMutationRepository,
) {
	fun assemble(userId: UUID, application: JobApplication): ApplicationResponse =
		assembleAt(userId, application, mutationRepository.historyWatermark(userId, application.id))

	fun assembleAt(userId: UUID, application: JobApplication, historyWatermark: Long): ApplicationResponse {
		val schedule = scheduleRepository.findForApplication(userId, application.id)
			?: throw NotFoundException("지원 일정 정보를 찾을 수 없습니다.")
		val page = PageRequest.of(0, HISTORY_SUMMARY_LIMIT)
		return toResponse(
			application = application,
			schedule = schedule,
			emails = emailRepository
					.findAllByUserIdAndApplicationIdAndRecordedOrderLessThanEqualOrderByRecordedOrderDesc(
					userId, application.id, historyWatermark, page,
				),
			activities = activityRepository
					.findAllByUserIdAndApplicationIdAndRecordedOrderLessThanEqualOrderByRecordedOrderDesc(
					userId, application.id, historyWatermark, page,
				),
			changes = changeRepository
					.findAllByUserIdAndApplicationIdAndRecordedOrderLessThanEqualOrderByRecordedOrderDesc(
					userId, application.id, historyWatermark, page,
				),
		)
	}

	fun restore(userId: UUID, snapshot: ApplicationResponse, historyWatermark: Long): ApplicationResponse =
		snapshot.copy(
			emails = emailRepository
					.findAllByUserIdAndApplicationIdAndRecordedOrderLessThanEqualOrderByRecordedOrderDesc(
					userId, snapshot.id, historyWatermark, PageRequest.of(0, HISTORY_SUMMARY_LIMIT),
				).map(::toEmailResponse),
			activities = activityRepository
					.findAllByUserIdAndApplicationIdAndRecordedOrderLessThanEqualOrderByRecordedOrderDesc(
					userId, snapshot.id, historyWatermark, PageRequest.of(0, HISTORY_SUMMARY_LIMIT),
				).map(::toActivityResponse),
			changes = changeRepository
					.findAllByUserIdAndApplicationIdAndRecordedOrderLessThanEqualOrderByRecordedOrderDesc(
					userId, snapshot.id, historyWatermark, PageRequest.of(0, HISTORY_SUMMARY_LIMIT),
				).map(::toChangeResponse),
		)

	fun assembleListItems(userId: UUID, applications: List<JobApplication>): List<ApplicationListItemResponse> {
		if (applications.isEmpty()) return emptyList()
		val applicationIds = applications.map(JobApplication::id)
		val schedules = scheduleRepository.findAllForApplications(userId, applicationIds)
			.associateBy(ApplicationSchedule::applicationId)
		return applications.map { application ->
			val schedule = schedules[application.id]
				?: throw NotFoundException("지원 일정 정보를 찾을 수 없습니다.")
			ApplicationListItemResponse(
				id = application.id,
				version = application.version,
				company = application.company,
				position = application.position,
				location = application.location,
				employmentType = application.employmentType,
				appliedAt = application.appliedAt,
				stage = application.stage.apiValue(),
				highestStageReached = application.highestStageReached.apiValue(),
				screeningPassed = application.screeningPassed,
				result = application.result.apiValue(),
				needsReview = application.needsReview,
				source = application.source,
				nextAction = schedule.action,
				scheduleType = schedule.scheduleType.apiValue(),
				nextActionAt = schedule.scheduledAt?.let { LocalDate.ofInstant(it, SEOUL) },
				nextActionCompleted = schedule.completed,
			)
		}
	}

	fun emailsPage(userId: UUID, applicationId: UUID, before: Long, limit: Int): HistoryPageResponse<EmailResponse> {
		val rows = emailRepository.findAllByUserIdAndApplicationIdAndRecordedOrderLessThanOrderByRecordedOrderDesc(
			userId, applicationId, before, PageRequest.of(0, limit + 1),
		)
		return historyPage(rows, limit, ApplicationEmail::recordedOrder, ::toEmailResponse)
	}

	fun activitiesPage(
		userId: UUID,
		applicationId: UUID,
		before: Long,
		limit: Int,
	): HistoryPageResponse<ActivityResponse> {
		val rows = activityRepository.findAllByUserIdAndApplicationIdAndRecordedOrderLessThanOrderByRecordedOrderDesc(
			userId, applicationId, before, PageRequest.of(0, limit + 1),
		)
		return historyPage(rows, limit, ApplicationActivity::recordedOrder, ::toActivityResponse)
	}

	fun changesPage(userId: UUID, applicationId: UUID, before: Long, limit: Int): HistoryPageResponse<ChangeResponse> {
		val rows = changeRepository.findAllByUserIdAndApplicationIdAndRecordedOrderLessThanOrderByRecordedOrderDesc(
			userId, applicationId, before, PageRequest.of(0, limit + 1),
		)
		return historyPage(rows, limit, ApplicationChange::recordedOrder, ::toChangeResponse)
	}

	private fun <T, R> historyPage(
		rows: List<T>,
		limit: Int,
		order: (T) -> Long,
		mapper: (T) -> R,
	): HistoryPageResponse<R> {
		val visible = rows.take(limit)
		return HistoryPageResponse(
			items = visible.map(mapper),
			nextCursor = if (rows.size > limit) visible.lastOrNull()?.let(order) else null,
		)
	}

	private fun toResponse(
		application: JobApplication,
		schedule: ApplicationSchedule,
		emails: List<ApplicationEmail>,
		activities: List<ApplicationActivity>,
		changes: List<ApplicationChange>,
	): ApplicationResponse = ApplicationResponse(
		id = application.id,
		version = application.version,
		company = application.company,
		position = application.position,
		location = application.location,
		employmentType = application.employmentType,
		appliedAt = application.appliedAt,
		stage = application.stage.apiValue(),
		highestStageReached = application.highestStageReached.apiValue(),
		screeningPassed = application.screeningPassed,
		result = application.result.apiValue(),
		needsReview = application.needsReview,
		source = application.source,
		nextAction = schedule.action,
		scheduleType = schedule.scheduleType.apiValue(),
		nextActionAt = schedule.scheduledAt?.let { LocalDate.ofInstant(it, SEOUL) },
		nextActionCompleted = schedule.completed,
		memo = application.memo,
		emails = emails.map(::toEmailResponse),
		activities = activities.map(::toActivityResponse),
		changes = changes.map(::toChangeResponse),
	)

	private fun toEmailResponse(email: ApplicationEmail): EmailResponse =
		EmailResponse(email.id, email.subject, email.sender, email.receivedAt, email.summary)

	private fun toActivityResponse(activity: ApplicationActivity): ActivityResponse = ActivityResponse(
		activity.id, activity.activityType.apiValue(), activity.title, activity.description, activity.occurredAt,
	)

	private fun toChangeResponse(change: ApplicationChange): ChangeResponse =
		ChangeResponse(change.id, change.title, change.description, change.occurredAt)

	companion object {
		private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
		const val HISTORY_SUMMARY_LIMIT = 50
	}
}
