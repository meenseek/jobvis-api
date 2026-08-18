package com.meenseek.jobvis.analytics

import java.math.BigDecimal
import java.time.LocalDate

data class ApplicationAnalyticsResponse(
	val from: LocalDate,
	val to: LocalDate,
	val total: Long,
	val active: Long,
	val offered: Long,
	val rejected: Long,
	val screeningPassed: Long,
	val reachedInterview: Long,
	val byStage: Map<String, Long>,
	val screeningPassRate: BigDecimal,
	val offerRate: BigDecimal,
)
