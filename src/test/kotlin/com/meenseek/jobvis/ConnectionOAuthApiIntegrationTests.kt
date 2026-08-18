package com.meenseek.jobvis

import com.meenseek.jobvis.connection.ConnectionProvider
import com.meenseek.jobvis.connection.OAuthConnectionClient
import com.meenseek.jobvis.connection.OAuthConnectionTokens
import com.meenseek.jobvis.common.ServiceUnavailableException
import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.auth.AuthRetentionWorker
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
	properties = [
		"jobvis.crypto.key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"jobvis.connections.gmail-client-id=test-client",
		"jobvis.connections.gmail-client-secret=test-secret",
	],
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ConnectionOAuthApiIntegrationTests.OAuthClientConfiguration::class)
class ConnectionOAuthApiIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val objectMapper: ObjectMapper,
	private val jdbcTemplate: JdbcTemplate,
	private val oauthExchangeAttempts: AtomicInteger,
	private val authRetentionWorker: AuthRetentionWorker,
) : PostgresIntegrationTest() {
	@Test
	fun `OAuth 연결은 state와 PKCE를 일회성으로 검증하고 토큰을 암호화한다`() {
		val userId = UUID.randomUUID()
		val beginBody = mockMvc.perform(
			post("/api/v1/connections/gmail/oauth/begin")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("redirectUri" to REDIRECT_URI))),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.provider").value("gmail"))
			.andReturn().response.contentAsString

		val authorizationUrl = objectMapper.readTree(beginBody).path("authorizationUrl").asString()
		val query = UriComponentsBuilder.fromUriString(authorizationUrl).build().queryParams
		val state = query.getFirst("state")
		assertThat(state).isNotBlank()
		assertThat(query.getFirst("code_challenge")).isNotBlank().isNotEqualTo("test-verifier")

		val completed = mockMvc.perform(
			post("/api/v1/connections/gmail/oauth/complete")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf("state" to state, "code" to "valid-code", "ongoingSyncConsent" to true),
					),
				),
		).andExpect(status().isCreated)
			.andExpect(jsonPath("$.accountEmail").value("candidate@gmail.com"))
			.andExpect(jsonPath("$.ongoingSyncConsent").value(true))
			.andReturn().response.contentAsString

		val connectionId = UUID.fromString(objectMapper.readTree(completed).path("id").asString())
		val credentials = jdbcTemplate.queryForMap(
			"SELECT encrypted_access_token, encrypted_refresh_token FROM external_connections WHERE id = ?",
			connectionId,
		)
		assertThat(credentials["encrypted_access_token"] as String).startsWith("v1:").doesNotContain("access-secret")
		assertThat(credentials["encrypted_refresh_token"] as String).startsWith("v1:").doesNotContain("refresh-secret")

		mockMvc.perform(
			post("/api/v1/connections/gmail/oauth/complete")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("state" to state, "code" to "valid-code"))),
		).andExpect(status().isBadRequest)
	}

	@Test
	fun `OAuth redirect URI는 정확히 등록된 값만 허용한다`() {
		mockMvc.perform(
			post("/api/v1/connections/gmail/oauth/begin")
				.header(USER_HEADER, UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("redirectUri" to "https://attacker.example/callback"))),
		).andExpect(status().isBadRequest)
	}

	@Test
	fun `OAuth 토큰 교환의 일시 장애는 state를 소비하지 않아 같은 요청을 재시도할 수 있다`() {
		oauthExchangeAttempts.set(0)
		val userId = UUID.randomUUID()
		val beginBody = mockMvc.perform(
			post("/api/v1/connections/gmail/oauth/begin")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("redirectUri" to REDIRECT_URI))),
		).andExpect(status().isOk).andReturn().response.contentAsString
		val state = UriComponentsBuilder.fromUriString(
			objectMapper.readTree(beginBody).path("authorizationUrl").asString(),
		).build().queryParams.getFirst("state")
		val body = objectMapper.writeValueAsString(
			mapOf("state" to state, "code" to "transient-code", "ongoingSyncConsent" to false),
		)

		mockMvc.perform(
			post("/api/v1/connections/gmail/oauth/complete")
				.header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON).content(body),
		).andExpect(status().isServiceUnavailable)
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT consumed_at IS NULL FROM oauth_challenges WHERE user_id = ?", Boolean::class.java, userId,
			),
		).isTrue()
		mockMvc.perform(
			post("/api/v1/connections/gmail/oauth/complete")
				.header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON).content(body),
		).andExpect(status().isCreated)
		assertThat(oauthExchangeAttempts.get()).isEqualTo(2)
	}

	@Test
	fun `잘못된 OAuth code는 state를 소비해 외부 교환을 반복하지 않는다`() {
		oauthExchangeAttempts.set(0)
		val userId = UUID.randomUUID()
		val beginBody = mockMvc.perform(
			post("/api/v1/connections/gmail/oauth/begin")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("redirectUri" to REDIRECT_URI))),
		).andExpect(status().isOk).andReturn().response.contentAsString
		val state = UriComponentsBuilder.fromUriString(
			objectMapper.readTree(beginBody).path("authorizationUrl").asString(),
		).build().queryParams.getFirst("state")
		val body = objectMapper.writeValueAsString(mapOf("state" to state, "code" to "invalid-code"))

		repeat(2) {
			mockMvc.perform(
				post("/api/v1/connections/gmail/oauth/complete")
					.header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON).content(body),
			).andExpect(status().isBadRequest)
		}
		assertThat(oauthExchangeAttempts.get()).isEqualTo(1)
	}

	@Test
	fun `원래 만료 시각이 지나도 활성 OAuth 교환 claim은 정리하지 않는다`() {
		val userId = UUID.randomUUID()
		mockMvc.perform(
			post("/api/v1/connections/gmail/oauth/begin")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("redirectUri" to REDIRECT_URI))),
		).andExpect(status().isOk)
		val challengeId = jdbcTemplate.queryForObject(
			"SELECT id FROM oauth_challenges WHERE user_id = ?", UUID::class.java, userId,
		) ?: error("OAuth challenge가 없습니다.")
		val now = Instant.now()
		jdbcTemplate.update(
			"""
				UPDATE oauth_challenges
				SET expires_at = ?, exchange_claim_token = ?, exchange_claim_expires_at = ?
				WHERE id = ?
			""".trimIndent(),
			java.sql.Timestamp.from(now.minusSeconds(1)),
			UUID.randomUUID(),
			java.sql.Timestamp.from(now.plusSeconds(60)),
			challengeId,
		)

		authRetentionWorker.purgeExpired()
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM oauth_challenges WHERE id = ?", Long::class.java, challengeId,
			),
		).isEqualTo(1)

		jdbcTemplate.update(
			"UPDATE oauth_challenges SET exchange_claim_expires_at = ? WHERE id = ?",
			java.sql.Timestamp.from(now.minusSeconds(1)), challengeId,
		)
		authRetentionWorker.purgeExpired()
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM oauth_challenges WHERE id = ?", Long::class.java, challengeId,
			),
		).isZero()
	}

	@TestConfiguration
	class OAuthClientConfiguration {
		@Bean
		fun oauthExchangeAttempts(): AtomicInteger = AtomicInteger()

		@Bean
		@Primary
		fun oauthConnectionClient(oauthExchangeAttempts: AtomicInteger): OAuthConnectionClient = object : OAuthConnectionClient {
			override fun isConfigured(provider: ConnectionProvider): Boolean = provider == ConnectionProvider.GMAIL

			override fun authorizationUrl(
				provider: ConnectionProvider,
				redirectUri: String,
				state: String,
				codeChallenge: String,
			): String = UriComponentsBuilder.fromUriString("https://accounts.example/authorize")
				.queryParam("state", state)
				.queryParam("code_challenge", codeChallenge)
				.build().toUriString()

			override fun exchange(
				provider: ConnectionProvider,
				redirectUri: String,
				code: String,
				codeVerifier: String,
			): OAuthConnectionTokens {
				if (code == "invalid-code") {
					oauthExchangeAttempts.incrementAndGet()
					throw BadRequestException("invalid OAuth code")
				}
				if (code == "transient-code" && oauthExchangeAttempts.incrementAndGet() == 1) {
					throw ServiceUnavailableException("temporary OAuth outage")
				}
				check(provider == ConnectionProvider.GMAIL)
				check(redirectUri == REDIRECT_URI)
				check(code == "valid-code" || code == "transient-code")
				check(codeVerifier.length >= 43)
				return OAuthConnectionTokens(
					accessToken = "access-secret",
					refreshToken = "refresh-secret",
					expiresAt = Instant.parse("2026-08-18T00:00:00Z"),
					scopes = setOf("https://www.googleapis.com/auth/gmail.readonly"),
					accountEmail = "candidate@gmail.com",
				)
			}
		}
	}

	private companion object {
		const val USER_HEADER = "X-Jobvis-User-Id"
		const val REDIRECT_URI = "http://localhost:3000/oauth/callback"
	}
}
