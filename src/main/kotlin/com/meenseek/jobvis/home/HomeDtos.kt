package com.meenseek.jobvis.home

import java.time.LocalDate
import java.util.UUID

data class HomeSummaryResponse(
	val date: LocalDate,
	val briefing: HomeBriefingResponse,
	val priorityItems: List<HomePriorityItemResponse>,
	val upcomingSchedules: List<HomeUpcomingScheduleResponse>,
	val activeApplications: List<HomeActiveApplicationResponse>,
)

data class HomeBriefingResponse(val reason: String, val count: Long)

data class HomePriorityItemResponse(
	val applicationId: UUID,
	val applicationVersion: Long,
	val company: String,
	val position: String,
	val reason: String,
	val scheduleType: String?,
	val nextActionAt: LocalDate?,
	val canComplete: Boolean,
)

data class HomeUpcomingScheduleResponse(
	val applicationId: UUID,
	val company: String,
	val position: String,
	val scheduleType: String,
	val date: LocalDate,
)

data class HomeActiveApplicationResponse(
	val applicationId: UUID,
	val company: String,
	val position: String,
	val status: String,
	val needsReview: Boolean,
	val nextActionAt: LocalDate?,
	val latestActivityTitle: String,
)
