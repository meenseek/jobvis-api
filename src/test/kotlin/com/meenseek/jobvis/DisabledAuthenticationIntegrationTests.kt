package com.meenseek.jobvis

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class DisabledAuthenticationIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
) : PostgresIntegrationTest() {
	@Test
	fun `local이 아닌 프로필에서는 사용자 헤더를 신뢰하지 않는다`() {
		mockMvc.perform(
			get("/api/v1/applications")
				.header("X-Jobvis-User-Id", UUID.randomUUID()),
		).andExpect(status().isUnauthorized)
	}
}
