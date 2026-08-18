package com.meenseek.jobvis.analytics

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.BusinessTime
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class AnalyticsService(
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
) {
	@Transactional(readOnly = true)
	fun summary(userId: UUID, requestedFrom: LocalDate?, requestedTo: LocalDate?): ApplicationAnalyticsResponse {
		val today = BusinessTime.today(clock)
		val from = requestedFrom ?: DEFAULT_FROM
		val to = requestedTo ?: today
		if (from.isAfter(to)) throw BadRequestException("통계 시작일은 종료일보다 늦을 수 없습니다.")
		if (to.isAfter(today)) throw BadRequestException("미래 기간은 통계에 포함할 수 없습니다.")
		val values = jdbcTemplate.queryForMap(
			"""
				SELECT
				    count(*) AS total,
				    count(*) FILTER (WHERE result = 'ACTIVE') AS active,
				    count(*) FILTER (WHERE result = 'OFFERED') AS offered,
				    count(*) FILTER (WHERE result = 'REJECTED') AS rejected,
				    count(*) FILTER (WHERE screening_passed) AS screening_passed,
				    count(*) FILTER (WHERE highest_stage_reached IN ('INTERVIEW', 'OFFER')) AS reached_interview,
				    count(*) FILTER (WHERE stage = 'APPLIED') AS stage_applied,
				    count(*) FILTER (WHERE stage = 'SCREENING') AS stage_screening,
				    count(*) FILTER (WHERE stage = 'INTERVIEW') AS stage_interview,
				    count(*) FILTER (WHERE stage = 'OFFER') AS stage_offer
				FROM applications
				WHERE user_id = ? AND applied_at BETWEEN ? AND ?
			""".trimIndent(),
			userId,
			from,
			to,
		)
		val total = values.long("total")
		val screeningPassed = values.long("screening_passed")
		val offered = values.long("offered")
		return ApplicationAnalyticsResponse(
			from = from,
			to = to,
			total = total,
			active = values.long("active"),
			offered = offered,
			rejected = values.long("rejected"),
			screeningPassed = screeningPassed,
			reachedInterview = values.long("reached_interview"),
			byStage = linkedMapOf(
				"applied" to values.long("stage_applied"),
				"screening" to values.long("stage_screening"),
				"interview" to values.long("stage_interview"),
				"offer" to values.long("stage_offer"),
			),
			screeningPassRate = rate(screeningPassed, total),
			offerRate = rate(offered, total),
		)
	}

	private fun rate(numerator: Long, denominator: Long): BigDecimal = if (denominator == 0L) {
		BigDecimal.ZERO.setScale(RATE_SCALE)
	} else {
		BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP)
	}

	private fun Map<String, Any?>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L

	private companion object {
		val DEFAULT_FROM: LocalDate = LocalDate.of(2000, 1, 1)
		const val RATE_SCALE = 4
	}
}
