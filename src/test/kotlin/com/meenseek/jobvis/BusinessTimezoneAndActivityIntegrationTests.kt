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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
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
	private val jdbcTemplate: JdbcTemplate,
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
								"position" to "백엔드", "status" to "applied",
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
							"mutationId" to UUID.randomUUID(),
							"connectionId" to connection.path("id").asString(),
							"dateFrom" to "2026-08-17", "dateTo" to "2026-08-17",
						),
					),
				),
			).andExpect(status().isAccepted)
	}

	@Test
	fun `홈 우선순위는 긴급 날짜와 회사 순이며 테스트와 면접 일정만 완료할 수 있다`() {
		val userId = UUID.randomUUID()
		val testApplication = createApplication(userId, "가 테스트", "test")
		val appliedApplication = createApplication(userId, "나 지원", "applied")
		val reviewApplication = createApplication(userId, "다 검토", "screening")

		patchSchedule(userId, testApplication.path("id").asString(), "테스트 제출")
		patchSchedule(userId, appliedApplication.path("id").asString(), "지원 확인")
		jdbcTemplate.update(
			"UPDATE applications SET needs_review = true WHERE user_id = ? AND id = ?",
			userId,
			UUID.fromString(reviewApplication.path("id").asString()),
		)

		mockMvc.perform(get("/api/v1/home/summary").header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.priorityItems[0].company").value("가 테스트"))
			.andExpect(jsonPath("$.priorityItems[0].canComplete").value(true))
			.andExpect(jsonPath("$.priorityItems[1].company").value("나 지원"))
			.andExpect(jsonPath("$.priorityItems[1].canComplete").value(false))
			.andExpect(jsonPath("$.priorityItems[2].company").value("다 검토"))
			.andExpect(jsonPath("$.priorityItems[2].reason").value("needsReview"))
			.andExpect(jsonPath("$.priorityItems[2].canComplete").value(false))
	}

	@Test
	fun `홈 다가오는 일정은 오늘부터 6일 뒤까지만 반환한다`() {
		val userId = UUID.randomUUID()
		val boundaryApplication = createApplication(userId, "경계 일정", "test")
		val outsideApplication = createApplication(userId, "범위 밖 일정", "interview")
		patchSchedule(
			userId, boundaryApplication.path("id").asString(), "경계 일정", "2026-08-23",
		)
		patchSchedule(
			userId, outsideApplication.path("id").asString(), "범위 밖 일정", "2026-08-24",
		)

		mockMvc.perform(get("/api/v1/home/summary").header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.briefing.reason").value("upcomingSchedule"))
			.andExpect(jsonPath("$.briefing.count").value(1))
			.andExpect(jsonPath("$.upcomingSchedules.length()").value(1))
			.andExpect(jsonPath("$.upcomingSchedules[0].company").value("경계 일정"))
			.andExpect(jsonPath("$.upcomingSchedules[0].date").value("2026-08-23"))
	}

	private fun createApplication(userId: UUID, company: String, statusValue: String) = objectMapper.readTree(
		mockMvc.perform(
			post("/api/v1/applications")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"mutationId" to UUID.randomUUID(),
							"company" to company,
							"position" to "백엔드",
							"status" to statusValue,
						),
					),
			),
		).andExpect(status().isCreated).andReturn().response.contentAsString,
	)

	private fun patchSchedule(
		userId: UUID,
		applicationId: String,
		title: String,
		date: String = "2026-08-16",
	) {
		mockMvc.perform(
			patch("/api/v1/applications/{id}/schedule", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"mutationId" to UUID.randomUUID(),
							"expectedVersion" to 0,
							"nextActionAt" to date,
							"nextActionTitle" to title,
						),
					),
			),
		).andExpect(status().isOk)
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
