package com.meenseek.jobvis

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ApplicationApiIntegrationTests.FixedClockConfiguration::class)
class ApplicationApiIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val objectMapper: ObjectMapper,
	private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {
	@Test
	fun `로컬 인증은 loopback의 올바른 UUID에서만 허용한다`() {
		mockMvc.perform(get("/api/v1/applications/counts")).andExpect(status().isUnauthorized)
		mockMvc.perform(get("/api/v1/applications/counts").header(USER_HEADER, "not-a-uuid"))
			.andExpect(status().isUnauthorized)
		mockMvc.perform(
			get("/api/v1/applications/counts")
				.header(USER_HEADER, UUID.randomUUID())
				.with { request -> request.apply { remoteAddr = "203.0.113.10" } },
		).andExpect(status().isUnauthorized)
		mockMvc.perform(get("/api/v1/applications/counts").header(USER_HEADER, UUID.randomUUID()))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.totalCount").value(0))
	}

	@Test
	fun `잘못된 JSON과 경로 UUID는 ProblemDetail을 반환한다`() {
		val userId = UUID.randomUUID()
		mockMvc.perform(
			post("/api/v1/applications")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{"),
		).andExpect(status().isBadRequest)
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(400))
		mockMvc.perform(get("/api/v1/applications/{id}", "not-a-uuid").header(USER_HEADER, userId))
			.andExpect(status().isBadRequest)
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
	}

	@Test
	fun `지원 생성은 test를 보존하고 같은 mutation을 재생하며 page에 노출한다`() {
		val userId = UUID.randomUUID()
		val mutationId = UUID.randomUUID()
		val created = create(userId, mutationId, "무신사", "백엔드 엔지니어", "test")
		assertThat(created.path("version").asLong()).isZero()
		assertThat(created.path("status").asString()).isEqualTo("test")
		assertThat(created.path("sourceType").asString()).isEqualTo("manual")
		assertThat(created.path("appliedAt").asString()).isEqualTo("2026-08-17")
		assertThat(create(userId, mutationId, "무신사", "백엔드 엔지니어", "test").path("id").asString())
			.isEqualTo(created.path("id").asString())
		mockMvc.perform(
			post("/api/v1/applications")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(mutationId, "다른 회사", "백엔드 엔지니어", "test")),
		).andExpect(status().isConflict)
		mockMvc.perform(
			get("/api/v1/applications/page")
				.header(USER_HEADER, userId)
				.param("status", "test")
				.param("limit", "50"),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].status").value("test"))
			.andExpect(jsonPath("$.filteredCount").value(1))
			.andExpect(jsonPath("$.totalCount").value(1))
		mockMvc.perform(get("/api/v1/applications").header(USER_HEADER, userId))
			.andExpect(status().isMethodNotAllowed)
	}

	@Test
	fun `동시 생성 재시도는 지원 하나만 만든다`() {
		val userId = UUID.randomUUID()
		val body = createBody(UUID.randomUUID(), "동시성 회사", "플랫폼 엔지니어", "applied")
		val ready = CountDownLatch(2)
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val futures = (1..2).map {
				executor.submit<String> {
					ready.countDown()
					check(start.await(10, TimeUnit.SECONDS))
					mockMvc.perform(
						post("/api/v1/applications")
							.header(USER_HEADER, userId)
							.contentType(MediaType.APPLICATION_JSON)
							.content(body),
					).andExpect(status().isCreated).andReturn().response.contentAsString
				}
			}
			check(ready.await(10, TimeUnit.SECONDS))
			start.countDown()
			assertThat(futures.map { json(it.get(15, TimeUnit.SECONDS)).path("id").asString() }.distinct())
				.hasSize(1)
		} finally {
			executor.shutdownNow()
		}
		mockMvc.perform(get("/api/v1/applications/counts").header(USER_HEADER, userId))
			.andExpect(jsonPath("$.totalCount").value(1))
	}

	@Test
	fun `상세 수정과 메모는 core와 cursor history를 분리한다`() {
		val userId = UUID.randomUUID()
		val applicationId = create(userId, UUID.randomUUID(), "무신사", "백엔드", "screening")
			.path("id").asString()
		val detailsBody = objectMapper.writeValueAsString(
			mapOf(
				"mutationId" to UUID.randomUUID(),
				"expectedVersion" to 0,
				"company" to "무신사",
				"position" to "서버 엔지니어",
				"location" to "서울",
				"employmentType" to "정규직",
				"appliedAt" to "2026-08-18",
			),
		)
		mockMvc.perform(
			patch("/api/v1/applications/{id}/details", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(detailsBody),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.position").value("서버 엔지니어"))
		mockMvc.perform(
			put("/api/v1/applications/{id}/memo", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf("mutationId" to UUID.randomUUID(), "expectedVersion" to 1, "memo" to "면접 준비"),
					),
			),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.memo").value("면접 준비"))
		mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.emails").doesNotExist())
			.andExpect(jsonPath("$.activities").doesNotExist())
			.andExpect(jsonPath("$.changes").doesNotExist())
		mockMvc.perform(
			get("/api/v1/applications/{id}/changes", applicationId)
				.header(USER_HEADER, userId)
				.param("limit", "2"),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.nextCursor").isNumber)
			.andExpect(jsonPath("$.totalCount").value(5))
	}

	@Test
	fun `상태 변경은 결과와 최고 도달 단계를 보존하고 history를 남긴다`() {
		val userId = UUID.randomUUID()
		val applicationId = create(userId, UUID.randomUUID(), "상태 회사", "개발자", "screening")
			.path("id").asString()
		assertThat(updateStatus(userId, applicationId, 0, "test").path("status").asString()).isEqualTo("test")
		assertThat(updateStatus(userId, applicationId, 1, "rejected").path("status").asString())
			.isEqualTo("rejected")
		assertThat(updateStatus(userId, applicationId, 2, "applied").path("status").asString())
			.isEqualTo("applied")
		assertThat(
			jdbcTemplate.queryForMap(
				"SELECT stage, highest_stage_reached, result FROM applications WHERE user_id = ? AND id = ?",
				userId,
				UUID.fromString(applicationId),
			),
		).containsEntry("stage", "APPLIED")
			.containsEntry("highest_stage_reached", "TEST")
			.containsEntry("result", "ACTIVE")
		mockMvc.perform(get("/api/v1/applications/{id}/changes", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(3))
	}

	@Test
	fun `부분 일정은 all-day 일정을 만들고 null을 거부하며 완료할 수 있다`() {
		val userId = UUID.randomUUID()
		val applicationId = create(userId, UUID.randomUUID(), "일정 회사", "개발자", "test")
			.path("id").asString()
		mockMvc.perform(get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isNotFound)
		mockMvc.perform(
			patch("/api/v1/applications/{id}/schedule", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"mutationId" to UUID.randomUUID(),
							"expectedVersion" to 0,
							"nextActionAt" to "2026-08-21",
							"nextActionTitle" to "코딩 테스트",
						),
					),
			),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.schedule.nextActionAt").value("2026-08-21"))
		mockMvc.perform(get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.allDay").value(true))
			.andExpect(jsonPath("$.date").value("2026-08-21"))
		mockMvc.perform(
			patch("/api/v1/applications/{id}/schedule", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					"""{"mutationId":"${UUID.randomUUID()}","expectedVersion":1,"nextActionAt":null}""",
				),
		).andExpect(status().isBadRequest)
		mockMvc.perform(
			post("/api/v1/applications/{id}/schedule/complete", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf("mutationId" to UUID.randomUUID(), "expectedVersion" to 1),
					),
			),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.schedule").isEmpty)
	}

	@Test
	fun `서울 화면 날짜가 바뀐 non-Seoul timed 일정은 offset 경계에서도 거부한다`() {
		val userId = UUID.randomUUID()
		val applicationId = create(userId, UUID.randomUUID(), "해외 일정 회사", "개발자", "interview")
			.path("id").asString()
		mockMvc.perform(
			put("/api/v1/applications/{id}/schedule", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf<String, Any?>(
							"mutationId" to UUID.randomUUID(),
							"expectedVersion" to 0,
							"scheduleType" to "interview",
							"action" to "LA 면접",
							"scheduledAt" to "2026-08-20T01:00:00Z",
							"endsAt" to null,
							"timezone" to "America/Los_Angeles",
							"location" to "",
							"description" to "",
						),
					),
			),
		).andExpect(status().isOk)

		mockMvc.perform(
			patch("/api/v1/applications/{id}/schedule", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"mutationId" to UUID.randomUUID(),
							"expectedVersion" to 1,
							"nextActionAt" to "2026-08-19",
						),
					),
			),
		).andExpect(status().isBadRequest)

		mockMvc.perform(
			patch("/api/v1/applications/{id}/schedule", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"mutationId" to UUID.randomUUID(),
							"expectedVersion" to 1,
							"nextActionTitle" to "LA 면접 준비",
						),
					),
			),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(2))
		mockMvc.perform(get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.action").value("LA 면접 준비"))
			.andExpect(jsonPath("$.scheduledAt").value("2026-08-20T01:00:00Z"))
	}

	@Test
	fun `활동 삭제는 버전과 mutation을 확인하고 별도 history를 갱신한다`() {
		val userId = UUID.randomUUID()
		val applicationId = create(userId, UUID.randomUUID(), "삭제 회사", "개발자", "applied")
			.path("id").asString()
		val activities = json(
			mockMvc.perform(get("/api/v1/applications/{id}/activities", applicationId).header(USER_HEADER, userId))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andReturn().response.contentAsString,
		)
		val activityId = activities.path("items").get(0).path("id").asString()
		val body = objectMapper.writeValueAsString(
			mapOf("mutationId" to UUID.randomUUID(), "expectedVersion" to 0),
		)
		repeat(2) {
			mockMvc.perform(
				delete("/api/v1/applications/{id}/activities/{activityId}", applicationId, activityId)
					.header(USER_HEADER, userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(body),
			).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
		}
		mockMvc.perform(get("/api/v1/applications/{id}/activities", applicationId).header(USER_HEADER, userId))
			.andExpect(jsonPath("$.items.length()").value(0))
	}

	@Test
	fun `페이지는 검색 필터와 테넌트 경계를 적용하고 잘못된 필터를 거부한다`() {
		val userId = UUID.randomUUID()
		create(userId, UUID.randomUUID(), "라인", "서버 개발자", "applied")
		create(userId, UUID.randomUUID(), "카카오", "플랫폼 개발자", "interview")
		create(UUID.randomUUID(), UUID.randomUUID(), "다른 사용자", "개발자", "applied")
		mockMvc.perform(
			get("/api/v1/applications/page")
				.header(USER_HEADER, userId)
				.param("q", "라인")
				.param("status", "application")
				.param("limit", "1"),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.items[0].company").value("라인"))
			.andExpect(jsonPath("$.filteredCount").value(1))
			.andExpect(jsonPath("$.totalCount").value(2))
		mockMvc.perform(
			get("/api/v1/applications/page").header(USER_HEADER, userId).param("status", "unknown"),
		).andExpect(status().isBadRequest)
	}

	@Test
	fun `단건과 일괄 검토 완료는 revision과 mutation으로 멱등 처리한다`() {
		val userId = UUID.randomUUID()
		val first = create(userId, UUID.randomUUID(), "검토1", "개발자", "applied")
		val second = create(userId, UUID.randomUUID(), "검토2", "개발자", "screening")
		jdbcTemplate.update("UPDATE applications SET needs_review = true WHERE user_id = ?", userId)
		jdbcTemplate.update(
			"""
				INSERT INTO application_review_states (user_id, review_revision, updated_at)
				VALUES (?, 1, ?)
				ON CONFLICT (user_id) DO UPDATE SET review_revision = 1, updated_at = EXCLUDED.updated_at
			""".trimIndent(),
			userId,
			Timestamp.from(NOW),
		)
		val singleBody = objectMapper.writeValueAsString(
			mapOf("mutationId" to UUID.randomUUID(), "expectedVersion" to 0),
		)
		repeat(2) {
			mockMvc.perform(
				post("/api/v1/applications/{id}/review/complete", first.path("id").asString())
					.header(USER_HEADER, userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(singleBody),
			).andExpect(jsonPath("$.needsReview").value(false))
		}
		val page = json(
			mockMvc.perform(
				get("/api/v1/applications/page").header(USER_HEADER, userId).param("status", "review"),
			).andReturn().response.contentAsString,
		)
		assertThat(page.path("items").size()).isEqualTo(1)
		assertThat(page.path("items").get(0).path("id").asString())
			.isEqualTo(second.path("id").asString())
		val bulkBody = objectMapper.writeValueAsString(
			mapOf(
				"mutationId" to UUID.randomUUID(),
				"expectedReviewRevision" to page.path("reviewRevision").asLong(),
			),
		)
		repeat(2) {
			mockMvc.perform(
				post("/api/v1/applications/review/complete-bulk")
					.header(USER_HEADER, userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(bulkBody),
			).andExpect(status().isOk)
				.andExpect(jsonPath("$.needsReviewCount").value(0))
		}
	}

	private fun create(
		userId: UUID,
		mutationId: UUID,
		company: String,
		position: String,
		statusValue: String,
	): JsonNode = json(
		mockMvc.perform(
			post("/api/v1/applications")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody(mutationId, company, position, statusValue)),
		).andExpect(status().isCreated).andReturn().response.contentAsString,
	)

	private fun createBody(
		mutationId: UUID,
		company: String,
		position: String,
		statusValue: String,
	): String = objectMapper.writeValueAsString(
		mapOf(
			"mutationId" to mutationId,
			"company" to company,
			"position" to position,
			"status" to statusValue,
		),
	)

	private fun updateStatus(
		userId: UUID,
		applicationId: String,
		expectedVersion: Long,
		statusValue: String,
	): JsonNode = json(
		mockMvc.perform(
			post("/api/v1/applications/{id}/status", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"mutationId" to UUID.randomUUID(),
							"expectedVersion" to expectedVersion,
							"status" to statusValue,
						),
					),
			),
		).andExpect(status().isOk).andReturn().response.contentAsString,
	)

	private fun json(value: String): JsonNode = objectMapper.readTree(value)

	@TestConfiguration(proxyBeanMethods = false)
	class FixedClockConfiguration {
		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
	}

	private companion object {
		const val USER_HEADER = "X-Jobvis-User-Id"
		val NOW: Instant = Instant.parse("2026-08-16T15:30:00Z")
	}
}
