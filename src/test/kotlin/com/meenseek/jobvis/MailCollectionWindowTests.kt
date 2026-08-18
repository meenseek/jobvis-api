package com.meenseek.jobvis

import com.meenseek.jobvis.imports.mailCollectionWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class MailCollectionWindowTests {
	@Test
	fun `Gmail 검색은 시작 경계를 1초 앞당기고 실제 수신 시각은 반개구간으로 필터링한다`() {
		val window = mailCollectionWindow(LocalDate.parse("2026-08-17"), LocalDate.parse("2026-08-17"))

		assertThat(window.fromInclusive).isEqualTo(Instant.parse("2026-08-16T15:00:00Z"))
		assertThat(window.gmailAfterEpochSecond).isEqualTo(window.fromInclusive.epochSecond - 1)
		assertThat(window.contains(window.fromInclusive.minusSeconds(1))).isFalse()
		assertThat(window.contains(window.fromInclusive)).isTrue()
		assertThat(window.contains(window.toExclusive.minusMillis(1))).isTrue()
		assertThat(window.contains(window.toExclusive)).isFalse()
	}
}
