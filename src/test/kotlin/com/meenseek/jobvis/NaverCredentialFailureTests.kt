package com.meenseek.jobvis

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ServiceUnavailableException
import com.meenseek.jobvis.connection.classifyNaverValidationFailure
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NaverCredentialFailureTests {
	@Test
	fun `네이버 인증 실패만 사용자 입력 오류로 분류한다`() {
		assertThat(classifyNaverValidationFailure(AuthenticationFailedException("invalid credential")))
			.isInstanceOf(BadRequestException::class.java)
	}

	@Test
	fun `네이버 네트워크와 서버 장애는 재시도 가능한 오류로 분류한다`() {
		assertThat(classifyNaverValidationFailure(MessagingException("read timed out")))
			.isInstanceOf(ServiceUnavailableException::class.java)
	}
}
