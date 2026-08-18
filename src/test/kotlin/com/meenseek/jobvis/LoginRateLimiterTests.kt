package com.meenseek.jobvis

import com.meenseek.jobvis.auth.LoginRateLimiter
import com.meenseek.jobvis.common.TooManyRequestsException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class LoginRateLimiterTests {
	@Test
	fun `추적하는 클라이언트 키는 설정된 메모리 상한을 넘지 않는다`() {
		val limiter = LoginRateLimiter(
			Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC),
			Duration.ofMinutes(10),
			100,
			2,
		)
		limiter.check("192.0.2.1", "challenge")
		limiter.check("192.0.2.2", "challenge")
		assertThatThrownBy { limiter.check("192.0.2.3", "challenge") }
			.isInstanceOf(TooManyRequestsException::class.java)
	}
}
