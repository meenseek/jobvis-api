package com.meenseek.jobvis

import com.meenseek.jobvis.application.ApplicationSchedule
import com.meenseek.jobvis.application.ScheduleType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ApplicationScheduleTests {
	@Test
	fun `날짜 화면 수정은 기존 시각 기간 종류와 세부값을 보존한다`() {
		val initialStart = Instant.parse("2026-08-19T01:00:00Z")
		val schedule = ApplicationSchedule.createImported(
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ScheduleType.TEST,
			"코딩 테스트", initialStart, initialStart.plusSeconds(7200), initialStart, initialStart,
		)
		schedule.update(
			ScheduleType.TEST,
			"코딩 테스트",
			initialStart,
			initialStart.plusSeconds(7200),
			"Asia/Seoul",
			"온라인",
			"링크 보존",
			initialStart,
		)

		val changed = schedule.updateFromDateView(
			nextActionAtPresent = true,
			nextActionAt = LocalDate.of(2026, 8, 21),
			nextActionTitlePresent = true,
			nextActionTitle = "최종 코딩 테스트",
			now = initialStart.plusSeconds(1),
		)

		assertThat(changed).isTrue()
		assertThat(schedule.scheduleType).isEqualTo(ScheduleType.TEST)
		assertThat(schedule.scheduledAt).isEqualTo(Instant.parse("2026-08-21T01:00:00Z"))
		assertThat(schedule.endsAt).isEqualTo(Instant.parse("2026-08-21T03:00:00Z"))
		assertThat(schedule.timezone).isEqualTo("Asia/Seoul")
		assertThat(schedule.location).isEqualTo("온라인")
		assertThat(schedule.description).isEqualTo("링크 보존")
	}

	@Test
	fun `빈 제목은 기존 제목을 유지하고 explicit null 날짜는 거부한다`() {
		val initialStart = Instant.parse("2026-08-19T01:00:00Z")
		val schedule = ApplicationSchedule.createImported(
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ScheduleType.TEST,
			"코딩 테스트", initialStart, null, initialStart, initialStart,
		)

		val changed = schedule.updateFromDateView(
			nextActionAtPresent = false,
			nextActionAt = null,
			nextActionTitlePresent = true,
			nextActionTitle = "",
			now = initialStart.plusSeconds(1),
		)
		assertThat(changed).isFalse()
		assertThat(schedule.action).isEqualTo("코딩 테스트")

		assertThatThrownBy {
			schedule.updateFromDateView(true, null, false, null, initialStart.plusSeconds(2))
		}.isInstanceOf(IllegalArgumentException::class.java)
	}
}
