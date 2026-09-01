package com.meenseek.jobvis

import com.meenseek.jobvis.auth.AuthSession
import com.meenseek.jobvis.auth.AuthSessionRepository
import com.meenseek.jobvis.auth.SessionCurrentUserProvider
import com.meenseek.jobvis.auth.TokenDigests
import com.meenseek.jobvis.auth.UserAccountRepository
import com.meenseek.jobvis.auth.trustedSiteUserUuid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

private const val TRUSTED_SITE_SECRET = "test-only-site-gateway-secret-32-bytes"

@SpringBootTest(properties = ["jobvis.auth.trusted-site-secret=$TRUSTED_SITE_SECRET"])
@AutoConfigureMockMvc
class DisabledAuthenticationIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val objectMapper: ObjectMapper,
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
	fun `검증된 Sites gateway 사용자만 안정적인 내부 사용자로 준비한다`() {
		val first = currentSiteUserId("site-user-1", TRUSTED_SITE_SECRET)
		val replay = currentSiteUserId("site-user-1", TRUSTED_SITE_SECRET)
		val other = currentSiteUserId("site-user-2", TRUSTED_SITE_SECRET)

		assertThat(first).isEqualTo(replay)
		assertThat(first).isNotEqualTo(other)
		assertThat(first).isNotEqualTo("site-user-1")
		assertThat(first).isEqualTo("90430c6f-ebce-8116-9c72-2c44085996ac")
		assertThat(first).isEqualTo(trustedSiteUserUuid("site-user-1").toString())
	}

	@Test
	fun `Sites gateway 헤더는 누락되거나 비밀이 다르면 거부한다`() {
		mockMvc.perform(
			get("/api/v1/applications/counts")
				.header(SessionCurrentUserProvider.SITE_USER_ID_HEADER, "site-user-1"),
		).andExpect(status().isUnauthorized)

		mockMvc.perform(
			get("/api/v1/applications/counts")
				.header(SessionCurrentUserProvider.SITE_USER_ID_HEADER, "site-user-1")
				.header(SessionCurrentUserProvider.SITE_SECRET_HEADER, "wrong-secret"),
		).andExpect(status().isUnauthorized)

		mockMvc.perform(
			get("/api/v1/applications/counts")
				.header(SessionCurrentUserProvider.SITE_SECRET_HEADER, TRUSTED_SITE_SECRET),
		).andExpect(status().isUnauthorized)
	}

	@Test
	fun `Sites gateway 비밀을 설정해도 기존 bearer session 인증을 유지한다`() {
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

	private fun currentSiteUserId(siteUserId: String, secret: String): String {
		val response = mockMvc.perform(
			get("/api/v1/auth/me")
				.header(SessionCurrentUserProvider.SITE_USER_ID_HEADER, siteUserId)
				.header(SessionCurrentUserProvider.SITE_SECRET_HEADER, secret),
		).andExpect(status().isOk)
			.andReturn().response.contentAsString
		return objectMapper.readTree(response).path("id").asString()
	}
}
