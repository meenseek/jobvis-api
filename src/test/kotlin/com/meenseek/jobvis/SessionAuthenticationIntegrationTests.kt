package com.meenseek.jobvis

import com.meenseek.jobvis.auth.AuthSession
import com.meenseek.jobvis.auth.AuthSessionRepository
import com.meenseek.jobvis.auth.TokenDigests
import com.meenseek.jobvis.auth.UserAccountRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class SessionAuthenticationIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val authSessionRepository: AuthSessionRepository,
	private val userAccountRepository: UserAccountRepository,
	private val transactionTemplate: TransactionTemplate,
) : PostgresIntegrationTest() {
	@Test
	fun `local이 아닌 프로필에서는 사용자 헤더를 신뢰하지 않는다`() {
		mockMvc.perform(
			get("/api/v1/applications/counts")
				.header("X-Jobvis-User-Id", UUID.randomUUID()),
		).andExpect(status().isUnauthorized)
	}

	@Test
	fun `bearer session 인증을 유지한다`() {
		val now = Instant.now()
		val userId = UUID.randomUUID()
		val accessToken = "existing-session-token"
		transactionTemplate.executeWithoutResult {
			userAccountRepository.provisionUser(userId, now)
			authSessionRepository.save(
				AuthSession.create(
					UUID.randomUUID(),
					userId,
					TokenDigests.sha256Hex(accessToken),
					now,
					now.plusSeconds(3600),
				),
			)
		}

		mockMvc.perform(
			get("/api/v1/applications/counts")
				.header("Authorization", "Bearer $accessToken"),
		).andExpect(status().isOk)
	}
}
