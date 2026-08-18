package com.meenseek.jobvis

import com.meenseek.jobvis.connection.ConnectionProvider
import com.meenseek.jobvis.connection.OAuthConnectionClient
import com.meenseek.jobvis.connection.OAuthConnectionTokens
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest(
	properties = [
		"jobvis.crypto.key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"jobvis.connections.oauth-max-outstanding-per-user=1",
		"jobvis.connections.oauth-start-rate-per-user=100",
	],
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ConnectionOAuthChallengeLimitIntegrationTests.OAuthConfiguration::class)
class ConnectionOAuthChallengeLimitIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val objectMapper: ObjectMapper,
	private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {
	@Test
	fun `인증된 사용자의 OAuth 연결 challenge도 대기 개수 상한을 적용한다`() {
		val userId = UUID.randomUUID()
		val now = Timestamp.from(Instant.now())
		jdbcTemplate.update("INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)", userId, now, now)
		val body = objectMapper.writeValueAsString(mapOf("redirectUri" to "http://localhost:3000/oauth/callback"))
		mockMvc.perform(
			post("/api/v1/connections/gmail/oauth/begin")
				.header("X-Jobvis-User-Id", userId).contentType(MediaType.APPLICATION_JSON).content(body),
		).andExpect(status().isOk)
		mockMvc.perform(
			post("/api/v1/connections/gmail/oauth/begin")
				.header("X-Jobvis-User-Id", userId).contentType(MediaType.APPLICATION_JSON).content(body),
		).andExpect(status().isTooManyRequests)
	}

	@TestConfiguration
	class OAuthConfiguration {
		@Bean
		@Primary
		fun oauthConnectionClient(): OAuthConnectionClient = object : OAuthConnectionClient {
			override fun isConfigured(provider: ConnectionProvider): Boolean = true
			override fun authorizationUrl(
				provider: ConnectionProvider,
				redirectUri: String,
				state: String,
				codeChallenge: String,
			): String = "https://example.com/oauth?state=$state"

			override fun exchange(
				provider: ConnectionProvider,
				redirectUri: String,
				code: String,
				codeVerifier: String,
			): OAuthConnectionTokens = error("사용하지 않습니다.")
		}
	}
}
