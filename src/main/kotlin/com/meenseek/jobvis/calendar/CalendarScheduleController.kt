package com.meenseek.jobvis.calendar

import com.meenseek.jobvis.auth.CurrentUserProvider
import com.meenseek.jobvis.common.BadRequestException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class CalendarSchedulePageResponse(val items: List<CalendarScheduleItemResponse>)

data class CalendarScheduleItemResponse(
	val applicationId: UUID,
	val company: String,
	val position: String,
	val status: String,
	val needsReview: Boolean,
	val title: String,
	val date: LocalDate,
)

@RestController
@RequestMapping("/api/v1/calendar")
class CalendarScheduleController(
	private val currentUserProvider: CurrentUserProvider,
	private val jdbcTemplate: JdbcTemplate,
) {
	@GetMapping("/schedules")
	@Transactional(readOnly = true)
	fun schedules(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
		httpRequest: HttpServletRequest,
	): CalendarSchedulePageResponse {
		if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 61) {
			throw BadRequestException("캘린더 조회 기간은 최대 62일이어야 합니다.")
		}
		val userId = currentUserProvider.currentUserId(httpRequest)
		val items = jdbcTemplate.query(
			"""
				SELECT application.id, application.company, application.position, application.needs_review,
				       CASE application.result
				           WHEN 'OFFERED' THEN 'offered'
				           WHEN 'REJECTED' THEN 'rejected'
				           ELSE lower(application.stage)
				       END AS status,
				       schedule.action,
				       COALESCE(schedule.scheduled_date, (schedule.scheduled_at AT TIME ZONE 'Asia/Seoul')::date) AS schedule_date
				FROM application_schedules schedule
				JOIN applications application
				  ON application.user_id = schedule.user_id AND application.id = schedule.application_id
				WHERE schedule.user_id = ? AND NOT schedule.completed
				  AND application.result <> 'REJECTED'
				  AND COALESCE(schedule.scheduled_date, (schedule.scheduled_at AT TIME ZONE 'Asia/Seoul')::date) BETWEEN ? AND ?
				ORDER BY schedule_date, application.id
			""".trimIndent(),
			{ row, _ ->
				CalendarScheduleItemResponse(
					row.getObject("id", UUID::class.java), row.getString("company"),
					row.getString("position"), row.getString("status"), row.getBoolean("needs_review"),
					row.getString("action"), row.getObject("schedule_date", LocalDate::class.java),
				)
			},
			userId, from, to,
		)
		return CalendarSchedulePageResponse(items)
	}
}
