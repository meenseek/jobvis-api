package com.meenseek.jobvis.auth

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LocalProfileSafetyGuardTests {
	@Test
	fun `loopback 주소만 허용한다`() {
		assertThatCode { LocalProfileSafetyGuard("127.0.0.1") }
			.doesNotThrowAnyException()

		assertThatThrownBy { LocalProfileSafetyGuard("0.0.0.0") }
			.isInstanceOf(IllegalStateException::class.java)
			.hasMessageContaining("loopback")
	}
}
