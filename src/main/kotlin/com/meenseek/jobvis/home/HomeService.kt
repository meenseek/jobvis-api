package com.meenseek.jobvis.home

import com.meenseek.jobvis.common.BusinessTime
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@Service
class HomeService(
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
) {
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	fun summary(userId: UUID): HomeSummaryResponse {
		val today = BusinessTime.today(clock)
		val counts = jdbcTemplate.queryForMap(
			"""
				SELECT
				    count(*) FILTER (WHERE application.needs_review) AS needs_review,
				    count(*) FILTER (
				        WHERE NOT application.needs_review
				          AND application.result <> 'REJECTED'
				          AND NOT schedule.completed
				          AND COALESCE(schedule.scheduled_date, (schedule.scheduled_at AT TIME ZONE 'Asia/Seoul')::date) <= ?
				    ) AS open_task,
				    count(*) FILTER (
				        WHERE NOT application.needs_review
				          AND application.result <> 'REJECTED'
				          AND NOT schedule.completed
				          AND COALESCE(schedule.scheduled_date, (schedule.scheduled_at AT TIME ZONE 'Asia/Seoul')::date) BETWEEN ? AND ?
				    ) AS upcoming_schedule
				FROM applications application
				LEFT JOIN application_schedules schedule
				  ON schedule.user_id = application.user_id AND schedule.application_id = application.id
				WHERE application.user_id = ?
			""".trimIndent(),
			today, today, today.plusDays(6), userId,
		)
		val briefing = listOf(
			"needsReview" to counts.long("needs_review"),
			"openTask" to counts.long("open_task"),
			"upcomingSchedule" to counts.long("upcoming_schedule"),
		).firstOrNull { it.second > 0 } ?: ("idle" to 0L)

		return HomeSummaryResponse(
			date = today,
			briefing = HomeBriefingResponse(briefing.first, briefing.second),
			priorityItems = priorityItems(userId, today),
			upcomingSchedules = upcomingSchedules(userId, today),
			activeApplications = activeApplications(userId),
		)
	}

	private fun priorityItems(userId: UUID, today: LocalDate): List<HomePriorityItemResponse> = jdbcTemplate.query(
		"""
			SELECT application.id, application.version, application.company, application.position,
			       CASE
			           WHEN application.needs_review THEN 'needsReview'
			           WHEN COALESCE(schedule.scheduled_date, (schedule.scheduled_at AT TIME ZONE 'Asia/Seoul')::date) < ? THEN 'overdue'
			           ELSE 'today'
			       END AS reason,
			       CASE WHEN application.needs_review THEN NULL ELSE lower(schedule.schedule_type) END AS schedule_type,
			       COALESCE(schedule.scheduled_date, (schedule.scheduled_at AT TIME ZONE 'Asia/Seoul')::date) AS next_action_at,
			       CASE
			           WHEN NOT application.needs_review
			             AND application.result = 'ACTIVE'
			             AND application.stage IN ('TEST', 'INTERVIEW')
			           THEN true ELSE false
			       END AS can_complete
			FROM applications application
			LEFT JOIN application_schedules schedule
			  ON schedule.user_id = application.user_id AND schedule.application_id = application.id
			WHERE application.user_id = ?
			  AND (
			      application.needs_review
			      OR (
			          application.result <> 'REJECTED' AND NOT schedule.completed
			          AND COALESCE(schedule.scheduled_date, (schedule.scheduled_at AT TIME ZONE 'Asia/Seoul')::date) <= ?
			      )
			  )
			ORDER BY CASE
			             WHEN COALESCE(schedule.scheduled_date, (schedule.scheduled_at AT TIME ZONE 'Asia/Seoul')::date) <= ?
			             THEN 0 ELSE 1
			         END,
			         COALESCE(schedule.scheduled_date, (schedule.scheduled_at AT TIME ZONE 'Asia/Seoul')::date) NULLS LAST,
			         application.company, application.id
			LIMIT 5
		""".trimIndent(),
		{ row, _ ->
			HomePriorityItemResponse(
				row.getObject("id", UUID::class.java), row.getLong("version"),
				row.getString("company"), row.getString("position"), row.getString("reason"),
				row.getString("schedule_type"), row.getObject("next_action_at", LocalDate::class.java),
				row.getBoolean("can_complete"),
			)
		},
		today, userId, today, today,
	)

	private fun upcomingSchedules(userId: UUID, today: LocalDate): List<HomeUpcomingScheduleResponse> =
		jdbcTemplate.query(
			"""
				SELECT application.id, application.company, application.position,
				       lower(schedule.schedule_type) AS schedule_type,
				       COALESCE(schedule.scheduled_date, (schedule.scheduled_at AT TIME ZONE 'Asia/Seoul')::date) AS schedule_date
				FROM applications application
				JOIN application_schedules schedule
				  ON schedule.user_id = application.user_id AND schedule.application_id = application.id
				WHERE application.user_id = ? AND application.result <> 'REJECTED'
				  AND NOT schedule.completed
				  AND COALESCE(schedule.scheduled_date, (schedule.scheduled_at AT TIME ZONE 'Asia/Seoul')::date)
				      BETWEEN ? AND ?
				ORDER BY schedule_date, application.id
				LIMIT 5
			""".trimIndent(),
			{ row, _ ->
				HomeUpcomingScheduleResponse(
					row.getObject("id", UUID::class.java), row.getString("company"),
					row.getString("position"), row.getString("schedule_type"),
					row.getObject("schedule_date", LocalDate::class.java),
				)
			},
			userId, today, today.plusDays(6),
		)

	private fun activeApplications(userId: UUID): List<HomeActiveApplicationResponse> = jdbcTemplate.query(
		"""
			SELECT application.id, application.company, application.position, application.needs_review,
			       CASE application.result
			           WHEN 'OFFERED' THEN 'offered'
			           WHEN 'REJECTED' THEN 'rejected'
			           ELSE lower(application.stage)
			       END AS status,
			       COALESCE(schedule.scheduled_date, (schedule.scheduled_at AT TIME ZONE 'Asia/Seoul')::date) AS next_action_at,
			       COALESCE(activity.title, application.source) AS latest_activity_title
			FROM applications application
			LEFT JOIN application_schedules schedule
			  ON schedule.user_id = application.user_id AND schedule.application_id = application.id
			 AND NOT schedule.completed
			LEFT JOIN LATERAL (
			    SELECT title FROM application_activities
			    WHERE user_id = application.user_id AND application_id = application.id
			    ORDER BY recorded_order DESC LIMIT 1
			) activity ON true
			WHERE application.user_id = ? AND application.result = 'ACTIVE'
			ORDER BY next_action_at NULLS LAST, application.applied_at DESC, application.id
			LIMIT 5
		""".trimIndent(),
		{ row, _ ->
			HomeActiveApplicationResponse(
				row.getObject("id", UUID::class.java), row.getString("company"),
				row.getString("position"), row.getString("status"), row.getBoolean("needs_review"),
				row.getObject("next_action_at", LocalDate::class.java), row.getString("latest_activity_title"),
			)
		},
		userId,
	)

	private fun Map<String, Any?>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
}
