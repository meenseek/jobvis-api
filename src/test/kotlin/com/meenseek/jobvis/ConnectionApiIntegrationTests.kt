package com.meenseek.jobvis

import com.meenseek.jobvis.connection.NaverCredentialValidator
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@SpringBootTest(
	properties = [
		"jobvis.crypto.key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	],
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ConnectionApiIntegrationTests.NaverValidatorConfiguration::class)
class ConnectionApiIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val objectMapper: ObjectMapper,
	private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {
	@Test
	fun `네이버 연결은 검증된 앱 비밀번호를 암호화하고 응답에는 노출하지 않는다`() {
		val userId = UUID.randomUUID()
		val response = mockMvc.perform(
			post("/api/v1/connections/naver")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"accountEmail" to "Meenseek5929@naver.com",
							"appPassword" to "naver-app-password",
							"ongoingSyncConsent" to false,
						),
					),
				),
		).andExpect(status().isCreated)
			.andExpect(jsonPath("$.provider").value("naver"))
			.andExpect(jsonPath("$.accountEmail").value("meenseek5929@naver.com"))
			.andExpect(jsonPath("$.status").value("connected"))
			.andExpect(jsonPath("$.version").value(0))
			.andReturn().response.contentAsString

		assertThat(response).doesNotContain("naver-app-password")
		val connectionId = UUID.fromString(objectMapper.readTree(response).path("id").asString())
		val encrypted = jdbcTemplate.queryForObject(
			"SELECT encrypted_app_password FROM external_connections WHERE id = ?",
			String::class.java,
			connectionId,
		)
		assertThat(encrypted).startsWith("v1:").doesNotContain("naver-app-password")

		mockMvc.perform(
			patch("/api/v1/connections/{id}/monitoring-consent", connectionId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("expectedVersion" to 0, "enabled" to true))),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.ongoingSyncConsent").value(true))
			.andExpect(jsonPath("$.version").value(1))

		mockMvc.perform(delete("/api/v1/connections/{id}", connectionId).header(USER_HEADER, userId))
			.andExpect(status().isNoContent)

		val revoked = jdbcTemplate.queryForMap(
			"SELECT status, encrypted_app_password, ongoing_sync_consent FROM external_connections WHERE id = ?",
			connectionId,
		)
		assertThat(revoked["status"]).isEqualTo("REVOKED")
		assertThat(revoked["encrypted_app_password"]).isNull()
		assertThat(revoked["ongoing_sync_consent"]).isEqualTo(false)
	}

	@Test
	fun `연결 기능 표시는 공급자 설정과 암호화 키 상태를 사실대로 반환한다`() {
		mockMvc.perform(
			get("/api/v1/connections/capabilities").header(USER_HEADER, UUID.randomUUID()),
		)
			.andExpect(status().isOk)
			.andExpect(jsonPath("$[0].provider").value("gmail"))
			.andExpect(jsonPath("$[0].available").value(false))
			.andExpect(jsonPath("$[2].provider").value("naver"))
			.andExpect(jsonPath("$[2].available").value(true))
	}

	@Test
	fun `네이버 계정 검증 제한은 사용자와 무관하게 데이터베이스에서 공유한다`() {
		val accountEmail = "shared-limit-${UUID.randomUUID()}@naver.com"
		repeat(5) {
			mockMvc.perform(
				post("/api/v1/connections/naver")
					.header(USER_HEADER, UUID.randomUUID())
					.contentType(MediaType.APPLICATION_JSON)
					.content(
						objectMapper.writeValueAsString(
							mapOf(
								"accountEmail" to accountEmail,
								"appPassword" to "naver-app-password",
								"ongoingSyncConsent" to false,
							),
						),
					),
			).andExpect(status().isCreated)
		}

		mockMvc.perform(
			post("/api/v1/connections/naver")
				.header(USER_HEADER, UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"accountEmail" to accountEmail.uppercase(),
							"appPassword" to "naver-app-password",
							"ongoingSyncConsent" to false,
						),
					),
				),
		).andExpect(status().isTooManyRequests)
	}

	@TestConfiguration
	class NaverValidatorConfiguration {
		@Bean
		@Primary
		fun naverCredentialValidator(): NaverCredentialValidator = NaverCredentialValidator { email, password ->
			check(email.endsWith("@naver.com"))
			check(password == "naver-app-password")
		}
	}

	private companion object {
		const val USER_HEADER = "X-Jobvis-User-Id"
	}
}
