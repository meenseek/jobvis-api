package com.meenseek.jobvis

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
		"jobvis.import.poll-delay=PT24H",
		"jobvis.import.cleanup-delay=PT24H",
	],
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(AnalyticsApiIntegrationTests.AnalyticsTestConfiguration::class)
class AnalyticsApiIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val objectMapper: ObjectMapper,
	private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {
	@Test
	fun `통계는 요청 사용자의 현재 지원 데이터만 기간 내에서 계산한다`() {
		val userId = UUID.randomUUID()
		val otherUserId = UUID.randomUUID()
		val offeredId = createApplication(userId, "오퍼 회사", "offer")
		val rejectedId = createApplication(userId, "탈락 회사", "screening")
		createApplication(userId, "진행 회사", "applied")
		createApplication(otherUserId, "다른 사용자 회사", "offer")
		jdbcTemplate.update(
			"UPDATE applications SET result = 'OFFERED', screening_passed = true WHERE id = ?",
			offeredId,
		)
		jdbcTemplate.update(
			"UPDATE applications SET result = 'REJECTED' WHERE id = ?",
			rejectedId,
		)

		mockMvc.perform(
			get("/api/v1/analytics/summary")
				.param("from", "2026-01-01")
				.param("to", "2026-08-17")
				.header(USER_HEADER, userId),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.active").value(1))
			.andExpect(jsonPath("$.offered").value(1))
			.andExpect(jsonPath("$.rejected").value(1))
			.andExpect(jsonPath("$.screeningPassed").value(1))
			.andExpect(jsonPath("$.reachedInterview").value(1))
			.andExpect(jsonPath("$.byStage.applied").value(1))
			.andExpect(jsonPath("$.byStage.screening").value(1))
			.andExpect(jsonPath("$.byStage.offer").value(1))
			.andExpect(jsonPath("$.screeningPassRate").value(0.3333))
			.andExpect(jsonPath("$.offerRate").value(0.3333))

		mockMvc.perform(
			get("/api/v1/analytics/summary")
				.param("from", "2026-08-18")
				.param("to", "2026-08-17")
				.header(USER_HEADER, userId),
		).andExpect(status().isBadRequest)
	}

	private fun createApplication(userId: UUID, company: String, stage: String): UUID {
		val response = mockMvc.perform(
			post("/api/v1/applications")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"mutationId" to UUID.randomUUID(),
							"company" to company,
							"position" to "백엔드 엔지니어",
							"stage" to stage,
						),
					),
				),
		).andExpect(status().isCreated).andReturn().response.contentAsString
		return UUID.fromString(objectMapper.readTree(response).path("id").asString())
	}

	@TestConfiguration
	class AnalyticsTestConfiguration {
		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
	}

	private companion object {
		const val USER_HEADER = "X-Jobvis-User-Id"
		val NOW: Instant = Instant.parse("2026-08-17T00:00:00Z")
	}
}
