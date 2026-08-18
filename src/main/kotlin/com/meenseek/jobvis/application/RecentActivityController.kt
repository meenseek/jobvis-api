package com.meenseek.jobvis.application

import com.meenseek.jobvis.auth.CurrentUserProvider
import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.BusinessTime
import jakarta.servlet.http.HttpServletRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

data class RecentActivityResponse(
	val id: UUID,
	val applicationId: UUID,
	val company: String,
	val position: String,
	val type: String,
	val title: String,
	val description: String,
	val occurredAt: Instant,
)

@Service
class RecentActivityService(
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
) {
	@Transactional(readOnly = true)
	fun list(
		userId: UUID,
		requestedFrom: LocalDate?,
		requestedTo: LocalDate?,
		typeValue: String?,
		limit: Int,
	): List<RecentActivityResponse> {
		val today = BusinessTime.today(clock)
		val from = requestedFrom ?: today
		val to = requestedTo ?: today
		if (from.isAfter(to) || to.isAfter(today) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
			throw BadRequestException("최근 활동 기간은 미래를 제외한 최대 31일이어야 합니다.")
		}
		if (limit !in 1..100) throw BadRequestException("limit은 1~100이어야 합니다.")
		val type = typeValue?.takeUnless(String::isBlank)?.trim()?.uppercase(Locale.ROOT)?.also { value ->
			if (ActivityType.entries.none { it.name == value }) {
				throw BadRequestException("활동 종류가 올바르지 않습니다.")
			}
		}
		val startsAt = from.atStartOfDay(BusinessTime.SEOUL).toInstant()
		val endsAt = to.plusDays(1).atStartOfDay(BusinessTime.SEOUL).toInstant()
		return jdbcTemplate.query(
			"""
				SELECT activity.id, activity.application_id, application.company, application.position,
				       activity.activity_type, activity.title, activity.description, activity.occurred_at
				FROM application_activities activity
				JOIN applications application
				  ON application.user_id = activity.user_id AND application.id = activity.application_id
				WHERE activity.user_id = ?
				  AND activity.occurred_at >= ? AND activity.occurred_at < ?
				  AND (CAST(? AS VARCHAR) IS NULL OR activity.activity_type = ?)
				ORDER BY activity.occurred_at DESC, activity.id
				LIMIT ?
			""".trimIndent(),
			{ resultSet, _ ->
				RecentActivityResponse(
					resultSet.getObject("id", UUID::class.java),
					resultSet.getObject("application_id", UUID::class.java),
					resultSet.getString("company"),
					resultSet.getString("position"),
					resultSet.getString("activity_type").lowercase(Locale.ROOT),
					resultSet.getString("title"),
					resultSet.getString("description"),
					resultSet.getTimestamp("occurred_at").toInstant(),
				)
			},
			userId, Timestamp.from(startsAt), Timestamp.from(endsAt), type, type, limit,
		)
	}

	private companion object {
		const val MAX_RANGE_DAYS = 30L
	}
}

@RestController
@RequestMapping("/api/v1/activities")
class RecentActivityController(
	private val currentUserProvider: CurrentUserProvider,
	private val service: RecentActivityService,
) {
	@GetMapping("/recent")
	fun recent(
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		from: LocalDate?,
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		to: LocalDate?,
		@RequestParam(required = false) type: String?,
		@RequestParam(required = false, defaultValue = "50") limit: Int,
		httpRequest: HttpServletRequest,
	): List<RecentActivityResponse> = service.list(
		currentUserProvider.currentUserId(httpRequest), from, to, type, limit,
	)
}
