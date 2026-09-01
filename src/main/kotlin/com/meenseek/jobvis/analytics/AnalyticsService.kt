package com.meenseek.jobvis.analytics

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.BusinessTime
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@Service
class AnalyticsService(
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
) {
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	fun summary(userId: UUID, requestedFrom: LocalDate?, requestedTo: LocalDate?): ApplicationAnalyticsResponse {
		val today = BusinessTime.today(clock)
		val queryFrom = requestedFrom ?: DEFAULT_FROM
		val to = requestedTo ?: today
		if (queryFrom.isAfter(to)) throw BadRequestException("통계 시작일은 종료일보다 늦을 수 없습니다.")
		if (to.isAfter(today)) throw BadRequestException("미래 기간은 통계에 포함할 수 없습니다.")
		val values = jdbcTemplate.queryForMap(
			"""
				SELECT
				    count(*) AS total,
				    count(*) FILTER (WHERE result = 'OFFERED') AS offered,
				    count(*) FILTER (WHERE screening_passed) AS screening_passed,
				    count(*) FILTER (WHERE highest_stage_reached IN ('INTERVIEW', 'OFFER')) AS reached_interview,
				    count(*) FILTER (WHERE source_type = 'GMAIL') AS source_gmail,
				    count(*) FILTER (WHERE source_type = 'NAVER') AS source_naver,
				    count(*) FILTER (WHERE source_type = 'OUTLOOK') AS source_outlook,
				    count(*) FILTER (WHERE source_type = 'MANUAL') AS source_manual,
				    count(*) FILTER (WHERE source_type = 'OTHER') AS source_other
				FROM applications
				WHERE user_id = ? AND applied_at BETWEEN ? AND ?
			""".trimIndent(),
			userId,
			queryFrom,
			to,
		)
		val monthlyCounts = jdbcTemplate.query(
			"""
				SELECT to_char(date_trunc('month', applied_at), 'YYYY-MM') AS month, count(*) AS count
				FROM applications
				WHERE user_id = ? AND applied_at BETWEEN ? AND ?
				GROUP BY date_trunc('month', applied_at)
			""".trimIndent(),
			{ row, _ -> YearMonth.parse(row.getString("month")) to row.getLong("count") },
			userId, queryFrom, to,
		).toMap()
		val endingMonth = YearMonth.from(to)
		val monthlyFlow = (5 downTo 0).map { offset ->
			val month = endingMonth.minusMonths(offset.toLong())
			MonthlyFlowResponse(month, monthlyCounts[month] ?: 0)
		}
		val sourceCounts = linkedMapOf(
			"gmail" to values.long("source_gmail"),
			"naver" to values.long("source_naver"),
			"outlook" to values.long("source_outlook"),
			"manual" to values.long("source_manual"),
			"other" to values.long("source_other"),
		).filterValues { it > 0 }
		return ApplicationAnalyticsResponse(
			from = requestedFrom,
			to = to,
			total = values.long("total"),
			screeningPassed = values.long("screening_passed"),
			reachedInterview = values.long("reached_interview"),
			offered = values.long("offered"),
			monthlyFlow = monthlyFlow,
			sourceCounts = sourceCounts,
		)
	}

	private fun Map<String, Any?>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L

	private companion object {
		val DEFAULT_FROM: LocalDate = LocalDate.of(2000, 1, 1)
	}
}
