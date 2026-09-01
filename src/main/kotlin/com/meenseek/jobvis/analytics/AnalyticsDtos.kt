package com.meenseek.jobvis.analytics

import java.time.LocalDate
import java.time.YearMonth

data class ApplicationAnalyticsResponse(
	val from: LocalDate?,
	val to: LocalDate,
	val total: Long,
	val screeningPassed: Long,
	val reachedInterview: Long,
	val offered: Long,
	val monthlyFlow: List<MonthlyFlowResponse>,
	val sourceCounts: Map<String, Long>,
)

data class MonthlyFlowResponse(val month: YearMonth, val count: Long)
