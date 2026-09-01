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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import tools.jackson.databind.ObjectMapper

@SpringBootTest(
	properties = [
		"jobvis.auth.rate-limit-per-ip=2",
		"server.forward-headers-strategy=framework",
	],
)
@AutoConfigureMockMvc
@Import(AuthRateLimitIntegrationTests.ConfiguredIdentityVerifier::class)
class AuthRateLimitIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val objectMapper: ObjectMapper,
) : PostgresIntegrationTest() {
	@Test
	fun `익명 로그인 챌린지는 IP별 요청 상한을 넘으면 429를 반환한다`() {
		repeat(2) {
			mockMvc.perform(
				post("/api/v1/auth/challenges")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(mapOf("provider" to "google"))),
			).andExpect(status().isCreated)
		}
		mockMvc.perform(
			post("/api/v1/auth/challenges")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("provider" to "google"))),
		).andExpect(status().isTooManyRequests)
			.andExpect(header().string("Retry-After", "600"))
	}

	@Test
	fun `신뢰 프록시가 전달한 서로 다른 클라이언트 주소는 별도 상한을 사용한다`() {
		repeat(2) {
			mockMvc.perform(
				post("/api/v1/auth/challenges")
					.header("X-Forwarded-For", "203.0.113.10")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(mapOf("provider" to "google"))),
			).andExpect(status().isCreated)
		}

		mockMvc.perform(
			post("/api/v1/auth/challenges")
				.header("X-Forwarded-For", "203.0.113.11")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("provider" to "google"))),
		).andExpect(status().isCreated)
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
