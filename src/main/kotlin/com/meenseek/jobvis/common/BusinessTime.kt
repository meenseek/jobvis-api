package com.meenseek.jobvis.common

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object BusinessTime {
	val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

	fun today(clock: Clock): LocalDate = LocalDate.ofInstant(Instant.now(clock), SEOUL)
}
