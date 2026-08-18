package com.meenseek.jobvis

import com.meenseek.jobvis.auth.IdentityTokenVerifier
import com.meenseek.jobvis.auth.LoginProvider
import com.meenseek.jobvis.auth.VerifiedIdentity
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
	properties = [
		"jobvis.auth.max-outstanding-challenges=1",
		"jobvis.auth.rate-limit-per-ip=100",
	],
)
@AutoConfigureMockMvc
@Import(AuthOutstandingChallengeIntegrationTests.ConfiguredIdentityVerifier::class)
class AuthOutstandingChallengeIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val objectMapper: ObjectMapper,
) : PostgresIntegrationTest() {
	@Test
	fun `미사용 로그인 챌린지의 전체 개수는 설정 상한을 넘지 않는다`() {
		val body = objectMapper.writeValueAsString(mapOf("provider" to "google"))
		mockMvc.perform(
			post("/api/v1/auth/challenges").contentType(MediaType.APPLICATION_JSON).content(body),
		).andExpect(status().isCreated)
		mockMvc.perform(
			post("/api/v1/auth/challenges").contentType(MediaType.APPLICATION_JSON).content(body),
		).andExpect(status().isTooManyRequests)
	}

	@TestConfiguration
	class ConfiguredIdentityVerifier {
		@Bean
		@Primary
		fun identityTokenVerifier(): IdentityTokenVerifier = object : IdentityTokenVerifier {
			override fun isConfigured(provider: LoginProvider): Boolean = true
			override fun verify(provider: LoginProvider, idToken: String, nonce: String): VerifiedIdentity =
				error("사용하지 않습니다.")
		}
	}
}
