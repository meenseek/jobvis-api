package com.meenseek.jobvis

import com.meenseek.jobvis.connection.NaverCredentialValidator
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest(
	properties = [
		"jobvis.crypto.key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"jobvis.import.poll-delay=PT24H",
		"jobvis.import.monitor-delay=PT24H",
	],
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(BusinessTimezoneAndActivityIntegrationTests.FixedTimeConfiguration::class)
class BusinessTimezoneAndActivityIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val objectMapper: ObjectMapper,
) : PostgresIntegrationTest() {
	@Test
	fun `KST 자정 직후의 지원 통계 가져오기와 홈 활동은 같은 영업일을 사용한다`() {
		val userId = UUID.randomUUID()
		val application = objectMapper.readTree(
			mockMvc.perform(
				post("/api/v1/applications")
					.header(USER_HEADER, userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(
						objectMapper.writeValueAsString(
							mapOf(
								"mutationId" to UUID.randomUUID(), "company" to "KST 회사",
								"position" to "백엔드", "stage" to "applied",
							),
						),
				),
			).andExpect(status().isCreated)
				.andExpect(jsonPath("$.appliedAt").value("2026-08-17"))
				.andReturn().response.contentAsString,
		)

		mockMvc.perform(get("/api/v1/analytics/summary").header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.to").value("2026-08-17"))
			.andExpect(jsonPath("$.total").value(1))
		mockMvc.perform(
			get("/api/v1/activities/recent")
				.param("type", "status")
				.param("limit", "10")
				.header(USER_HEADER, userId),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$[0].applicationId").value(application.path("id").asString()))

		val connection = objectMapper.readTree(
			mockMvc.perform(
				post("/api/v1/connections/naver")
					.header(USER_HEADER, userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(
						objectMapper.writeValueAsString(
							mapOf("accountEmail" to "kst@naver.com", "appPassword" to "test-password"),
						),
					),
				)
					.andExpect(status().isCreated).andReturn().response.contentAsString,
		)
		mockMvc.perform(
			post("/api/v1/import-runs")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"connectionId" to connection.path("id").asString(),
							"dateFrom" to "2026-08-17", "dateTo" to "2026-08-17",
						),
					),
				),
			).andExpect(status().isAccepted)
	}

	@TestConfiguration
	class FixedTimeConfiguration {
		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-08-16T15:30:00Z"), ZoneOffset.UTC)

		@Bean
		@Primary
		fun naverCredentialValidator(): NaverCredentialValidator = NaverCredentialValidator { _, _ -> }
	}

	private companion object {
		const val USER_HEADER = "X-Jobvis-User-Id"
	}
}
