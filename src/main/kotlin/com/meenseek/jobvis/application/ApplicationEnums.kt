package com.meenseek.jobvis.application

import com.meenseek.jobvis.common.BadRequestException
import java.util.Locale

enum class ApplicationStage(
	val label: String,
	private val rank: Int,
) {
	APPLIED("지원 완료", 0),
	SCREENING("서류 검토", 1),
	INTERVIEW("면접 진행", 2),
	OFFER("처우 협의", 3),
	;

	fun highest(other: ApplicationStage): ApplicationStage = if (rank >= other.rank) this else other

	fun passedScreeningByProgress(): Boolean = rank >= INTERVIEW.rank

	fun apiValue(): String = name.lowercase(Locale.ROOT)

	companion object {
		fun fromApiValue(value: String): ApplicationStage =
			runCatching { valueOf(value.trim().uppercase(Locale.ROOT)) }
				.getOrElse { throw BadRequestException("지원 단계가 올바르지 않습니다.") }
	}
}

enum class ApplicationResult {
	ACTIVE,
	OFFERED,
	REJECTED,
	;

	fun apiValue(): String = name.lowercase(Locale.ROOT)

	companion object {
		fun fromApiValue(value: String): ApplicationResult =
			runCatching { valueOf(value.trim().uppercase(Locale.ROOT)) }
				.getOrElse { throw BadRequestException("지원 결과가 올바르지 않습니다.") }
	}
}

enum class ScheduleType {
	APPLICATION,
	TEST,
	INTERVIEW,
	FOLLOWUP,
	OTHER,
	;

	fun apiValue(): String = name.lowercase(Locale.ROOT)

	companion object {
		fun fromApiValue(value: String): ScheduleType =
			runCatching { valueOf(value.trim().uppercase(Locale.ROOT)) }
				.getOrElse { throw BadRequestException("일정 종류가 올바르지 않습니다.") }
	}
}

enum class ActivityType {
	EMAIL,
	NOTE,
	STATUS,
	TASK,
	;

	fun apiValue(): String = name.lowercase(Locale.ROOT)
}
