package com.meenseek.jobvis.application

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
		return toResponse(application, schedule)
	}

	fun restore(userId: UUID, snapshot: ApplicationResponse, historyWatermark: Long): ApplicationResponse =
		snapshot

	fun assembleListItems(userId: UUID, applications: List<JobApplication>): List<ApplicationListItemResponse> {
		if (applications.isEmpty()) return emptyList()
		return applications.map { application ->
			ApplicationListItemResponse(
				id = application.id,
				version = application.version,
				company = application.company,
				position = application.position,
				appliedAt = application.appliedAt,
				status = application.currentStatusValue(),
				needsReview = application.needsReview,
				source = application.source,
			)
		}
	}

	fun emailsPage(userId: UUID, applicationId: UUID, before: Long, limit: Int): HistoryPageResponse<EmailResponse> {
		val rows = emailRepository.findAllByUserIdAndApplicationIdAndRecordedOrderLessThanOrderByRecordedOrderDesc(
			userId, applicationId, before, PageRequest.of(0, limit + 1),
		)
		return historyPage(
			rows, limit, ApplicationEmail::recordedOrder, ::toEmailResponse,
			emailRepository.countByUserIdAndApplicationId(userId, applicationId),
		)
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
		return historyPage(rows, limit, ApplicationActivity::recordedOrder, ::toActivityResponse, null)
	}

	fun changesPage(userId: UUID, applicationId: UUID, before: Long, limit: Int): HistoryPageResponse<ChangeResponse> {
		val rows = changeRepository.findAllByUserIdAndApplicationIdAndRecordedOrderLessThanOrderByRecordedOrderDesc(
			userId, applicationId, before, PageRequest.of(0, limit + 1),
		)
		return historyPage(
			rows, limit, ApplicationChange::recordedOrder, ::toChangeResponse,
			changeRepository.countByUserIdAndApplicationId(userId, applicationId),
		)
	}

	private fun <T, R> historyPage(
		rows: List<T>,
		limit: Int,
		order: (T) -> Long,
		mapper: (T) -> R,
		totalCount: Long?,
	): HistoryPageResponse<R> {
		val visible = rows.take(limit)
		return HistoryPageResponse(
			items = visible.map(mapper),
			nextCursor = if (rows.size > limit) visible.lastOrNull()?.let(order) else null,
			totalCount = totalCount,
		)
	}

	private fun toResponse(
		application: JobApplication,
		schedule: ApplicationSchedule?,
	): ApplicationResponse = ApplicationResponse(
		id = application.id,
		version = application.version,
		company = application.company,
		position = application.position,
		location = application.location,
		employmentType = application.employmentType,
		appliedAt = application.appliedAt,
		status = application.currentStatusValue(),
		needsReview = application.needsReview,
		source = application.source,
		sourceType = application.sourceType.apiValue(),
		schedule = schedule?.takeUnless { it.completed }?.let {
			ApplicationScheduleSummaryResponse(
				it.action,
				requireNotNull(
					it.scheduledDate
						?: it.scheduledAt?.let { scheduledAt -> LocalDate.ofInstant(scheduledAt, SEOUL) },
				) { "일정 날짜가 없습니다: ${it.id}" },
			)
		},
		memo = application.memo,
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
	}
}
