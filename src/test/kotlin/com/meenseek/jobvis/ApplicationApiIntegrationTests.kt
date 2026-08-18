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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
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
	fun `로컬 인증은 loopback과 올바른 UUID에서만 허용한다`() {
		mockMvc.perform(get("/api/v1/applications"))
			.andExpect(status().isUnauthorized)

		mockMvc.perform(get("/api/v1/applications").header(USER_HEADER, "not-a-uuid"))
			.andExpect(status().isUnauthorized)

		mockMvc.perform(
			get("/api/v1/applications")
				.header(USER_HEADER, UUID.randomUUID())
				.with { request -> request.apply { remoteAddr = "203.0.113.10" } },
		).andExpect(status().isUnauthorized)

		mockMvc.perform(get("/api/v1/applications").header(USER_HEADER, UUID.randomUUID()))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$").isArray)
	}

	@Test
	fun `지원 생성은 기본 일정과 활동을 원자적으로 만들고 재시도해도 중복되지 않는다`() {
		val userId = UUID.randomUUID()
		val mutationId = UUID.randomUUID()

		val created = create(userId, mutationId, "무신사", "백엔드 엔지니어", "screening")
		assertThat(created.path("version").asLong()).isZero()
		assertThat(created.path("appliedAt").asString()).isEqualTo("2026-08-17")
		assertThat(created.path("nextAction").asString()).isEqualTo("세부 정보 보완")
		assertThat(created.path("scheduleType").asString()).isEqualTo("application")
		assertThat(created.path("nextActionAt").asString()).isEqualTo("2026-08-17")
		assertThat(created.path("nextActionCompleted").asBoolean()).isFalse()
		assertThat(created.path("activities").get(0).path("title").asString())
			.isEqualTo("지원 이력을 추가했습니다")

		create(userId, mutationId, "무신사", "백엔드 엔지니어", "screening")
		mockMvc.perform(get("/api/v1/applications").header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(header().string("X-Jobvis-Limit", "200"))
			.andExpect(header().string("X-Jobvis-Has-Next", "false"))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].activities").doesNotExist())
			.andExpect(jsonPath("$[0].changes").doesNotExist())
			.andExpect(jsonPath("$[0].emails").doesNotExist())

		mockMvc.perform(
			get("/api/v1/applications/{id}", created.path("id").asString())
				.header(USER_HEADER, userId),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.activities.length()").value(1))

		mockMvc.perform(
			post("/api/v1/applications")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						CreateBody(mutationId, "다른 회사", "백엔드 엔지니어", "screening"),
					),
				),
		).andExpect(status().isConflict)
	}

	@Test
	fun `동시에 들어온 같은 mutation 요청은 하나를 반영하고 같은 결과를 재생한다`() {
		val userId = UUID.randomUUID()
		val mutationId = UUID.randomUUID()
		val requestBody = objectMapper.writeValueAsString(
			CreateBody(mutationId, "동시성 회사", "플랫폼 엔지니어", "applied"),
		)
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
							.content(requestBody),
					).andExpect(status().isCreated)
						.andReturn().response.contentAsString
				}
			}
			check(ready.await(10, TimeUnit.SECONDS))
			start.countDown()
			val responses = futures.map { json(it.get(15, TimeUnit.SECONDS)) }
			assertThat(responses.map { it.path("id").asString() }.distinct()).hasSize(1)
		} finally {
			executor.shutdownNow()
		}

		mockMvc.perform(get("/api/v1/applications").header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.length()").value(1))
	}

	@Test
	fun `Kotlin non-null 요청값이 빠지거나 null이면 400으로 거부한다`() {
		val userId = UUID.randomUUID()
		mockMvc.perform(
			post("/api/v1/applications")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"company":"회사","position":"개발자","stage":"applied"}"""),
		).andExpect(status().isBadRequest)

		mockMvc.perform(
			post("/api/v1/applications")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					"""
						{
						  "mutationId":"${UUID.randomUUID()}",
						  "company":null,
						  "position":"개발자",
						  "stage":"applied"
						}
					""".trimIndent(),
				),
		).andExpect(status().isBadRequest)

		val created = create(userId, UUID.randomUUID(), "회사", "개발자", "applied")
		val applicationId = created.path("id").asString()
		val mutationId = UUID.randomUUID()
		mockMvc.perform(
			put("/api/v1/applications/{id}/memo", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"mutationId":"$mutationId","memo":"메모"}"""),
		).andExpect(status().isBadRequest)

		mockMvc.perform(
			put("/api/v1/applications/{id}/memo", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"mutationId":"$mutationId","expectedVersion":null,"memo":"메모"}"""),
		).andExpect(status().isBadRequest)
	}

	@Test
	fun `상세 수정은 바뀐 필드만 이전 값과 결과로 기록하고 버전 충돌을 막는다`() {
		val userId = UUID.randomUUID()
		val created = create(userId, UUID.randomUUID(), "무신사", "백엔드 엔지니어", "applied")
		val applicationId = created.path("id").asString()
		val mutationId = UUID.randomUUID()
		val body = objectMapper.writeValueAsString(
			DetailsBody(mutationId, 0, "무신사", "서버 엔지니어", "서울", "정규직"),
		)

		val response = mockMvc.perform(
			patch("/api/v1/applications/{id}/details", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.changes.length()").value(3))
			.andReturn().response.contentAsString
		assertThat(response).contains("백엔드 엔지니어 → 서버 엔지니어")
		assertThat(response).contains("근무지 미입력 → 서울")
		assertThat(response).contains("고용 형태 미입력 → 정규직")

		mockMvc.perform(
			patch("/api/v1/applications/{id}/details", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.changes.length()").value(3))

		mockMvc.perform(
			put("/api/v1/applications/{id}/memo", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(MemoBody(UUID.randomUUID(), 0, "채용 담당자에게 회신"))),
		).andExpect(status().isConflict)
	}

	@Test
	fun `완료된 mutation 재시도는 이후 변경이 아니라 최초 응답을 재생한다`() {
		val userId = UUID.randomUUID()
		val created = create(userId, UUID.randomUUID(), "회사", "개발자", "applied")
		val applicationId = created.path("id").asString()
		val firstMutation = UUID.randomUUID()
		val firstBody = objectMapper.writeValueAsString(MemoBody(firstMutation, 0, "첫 메모"))
		mockMvc.perform(
			put("/api/v1/applications/{id}/memo", applicationId)
				.header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON).content(firstBody),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.memo").value("첫 메모"))

		mockMvc.perform(
			put("/api/v1/applications/{id}/memo", applicationId)
				.header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(MemoBody(UUID.randomUUID(), 1, "두 번째 메모"))),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(2))

		val replayed = mockMvc.perform(
			put("/api/v1/applications/{id}/memo", applicationId)
				.header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON).content(firstBody),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.memo").value("첫 메모"))
			.andExpect(jsonPath("$.changes.length()").value(1))
			.andReturn().response.contentAsString
		assertThat(replayed).contains("내용 없음 → 첫 메모").doesNotContain("첫 메모 → 두 번째 메모")
	}

	@Test
	fun `상세 요약은 이력을 제한하고 전체 이력은 cursor로 페이지 조회한다`() {
		val userId = UUID.randomUUID()
		val created = create(userId, UUID.randomUUID(), "페이지 회사", "개발자", "applied")
		val applicationId = UUID.fromString(created.path("id").asString())
		repeat(60) { index ->
			jdbcTemplate.update(
				"""
					INSERT INTO application_changes (
					    id, user_id, application_id, mutation_id, field_key, title,
					    before_value, after_value, occurred_at, created_at
					) VALUES (?, ?, ?, ?, 'memo', '메모', ?, ?, ?, ?)
				""".trimIndent(),
				UUID.randomUUID(), userId, applicationId, UUID.randomUUID(),
				"이전 $index", "이후 $index", Timestamp.from(NOW), Timestamp.from(NOW),
			)
		}

		mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.changes.length()").value(50))

		val firstPage = json(
			mockMvc.perform(
				get("/api/v1/applications/{id}/changes", applicationId)
					.param("limit", "20").header(USER_HEADER, userId),
			).andExpect(status().isOk)
				.andExpect(jsonPath("$.items.length()").value(20))
				.andExpect(jsonPath("$.nextCursor").isNumber)
				.andReturn().response.contentAsString,
		)
		val secondPage = json(
			mockMvc.perform(
				get("/api/v1/applications/{id}/changes", applicationId)
					.param("limit", "20")
					.param("before", firstPage.path("nextCursor").asLong().toString())
					.header(USER_HEADER, userId),
			).andExpect(status().isOk)
				.andExpect(jsonPath("$.items.length()").value(20))
				.andReturn().response.contentAsString,
		)
		val firstId = firstPage.path("items").get(0).path("id").asString()
		assertThat(secondPage.toString()).doesNotContain(firstId)

		mockMvc.perform(
			get("/api/v1/applications/page").param("limit", "1").header(USER_HEADER, userId),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.limit").value(1))
			.andExpect(jsonPath("$.hasNext").value(false))
		mockMvc.perform(
			get("/api/v1/applications/page").param("limit", "101").header(USER_HEADER, userId),
		).andExpect(status().isBadRequest)
	}

	@Test
	fun `변경기록 저장에 실패하면 지원 수정과 버전 증가도 롤백한다`() {
		val userId = UUID.randomUUID()
		val created = create(userId, UUID.randomUUID(), "원티드", "백엔드 엔지니어", "applied")
		val applicationId = UUID.fromString(created.path("id").asString())
		val mutationId = UUID.randomUUID()
		jdbcTemplate.update(
			"""
				INSERT INTO application_changes (
				    id, user_id, application_id, mutation_id, field_key, title,
				    before_value, after_value, occurred_at, created_at
				) VALUES (?, ?, ?, ?, 'position', '포지션', '기존', '충돌', ?, ?)
			""".trimIndent(),
			UUID.randomUUID(),
			userId,
			applicationId,
			mutationId,
			Timestamp.from(NOW),
			Timestamp.from(NOW),
		)

		mockMvc.perform(
			patch("/api/v1/applications/{id}/details", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						DetailsBody(
							mutationId,
							0,
							"원티드",
							"서버 엔지니어",
							"근무지 미입력",
							"고용 형태 미입력",
						),
					),
				),
		).andExpect(status().isConflict)

		mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.position").value("백엔드 엔지니어"))
			.andExpect(jsonPath("$.version").value(0))
	}

	@Test
	fun `메모와 일정 완료는 noop과 중복 재시도에서 이력을 중복하지 않는다`() {
		val userId = UUID.randomUUID()
		val created = create(userId, UUID.randomUUID(), "토스", "플랫폼 엔지니어", "interview")
		val applicationId = created.path("id").asString()

		val noOpMemo = json(
			mockMvc.perform(
				put("/api/v1/applications/{id}/memo", applicationId)
					.header(USER_HEADER, userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(MemoBody(UUID.randomUUID(), 0, ""))),
			).andExpect(status().isOk)
				.andReturn().response.contentAsString,
		)
		assertThat(noOpMemo.path("version").asLong()).isZero()
		assertThat(noOpMemo.path("changes").size()).isZero()

		val memo = json(
			mockMvc.perform(
				put("/api/v1/applications/{id}/memo", applicationId)
					.header(USER_HEADER, userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(
						objectMapper.writeValueAsString(MemoBody(UUID.randomUUID(), 0, "과제 범위 확인")),
					),
			).andExpect(status().isOk)
				.andReturn().response.contentAsString,
		)
		assertThat(memo.path("version").asLong()).isEqualTo(1)
		assertThat(memo.path("changes").get(0).path("description").asString())
			.isEqualTo("내용 없음 → 과제 범위 확인")

		val scheduleMutation = UUID.randomUUID()
		val scheduleBody = MutationBody(scheduleMutation, 1)
		val completed = json(
			mockMvc.perform(
				post("/api/v1/applications/{id}/schedule/complete", applicationId)
					.header(USER_HEADER, userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(scheduleBody)),
			).andExpect(status().isOk)
				.andReturn().response.contentAsString,
		)
		assertThat(completed.path("version").asLong()).isEqualTo(2)
		assertThat(completed.path("nextActionCompleted").asBoolean()).isTrue()
		assertThat(completed.path("activities").size()).isEqualTo(2)
		assertThat(completed.path("changes").toString()).contains("미완료 → 완료")

		mockMvc.perform(
			post("/api/v1/applications/{id}/schedule/complete", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(scheduleBody)),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.activities.length()").value(2))
			.andExpect(jsonPath("$.changes.length()").value(2))
	}

	@Test
	fun `상태 전이는 과거 최고 단계와 서류 통과를 보존한다`() {
		val userId = UUID.randomUUID()
		val created = create(userId, UUID.randomUUID(), "네이버", "서버 개발자", "interview")
		val applicationId = created.path("id").asString()

		val rejected = updateStatus(userId, applicationId, UUID.randomUUID(), 0, "rejected")
		assertThat(rejected.path("stage").asString()).isEqualTo("interview")
		assertThat(rejected.path("highestStageReached").asString()).isEqualTo("interview")
		assertThat(rejected.path("screeningPassed").asBoolean()).isTrue()
		assertThat(rejected.path("result").asString()).isEqualTo("rejected")

		val reopened = updateStatus(userId, applicationId, UUID.randomUUID(), 1, "applied")
		assertThat(reopened.path("stage").asString()).isEqualTo("applied")
		assertThat(reopened.path("highestStageReached").asString()).isEqualTo("interview")
		assertThat(reopened.path("screeningPassed").asBoolean()).isTrue()
		assertThat(reopened.path("result").asString()).isEqualTo("active")

		val offered = updateStatus(userId, applicationId, UUID.randomUUID(), 2, "offered")
		assertThat(offered.path("stage").asString()).isEqualTo("offer")
		assertThat(offered.path("highestStageReached").asString()).isEqualTo("offer")
		assertThat(offered.path("screeningPassed").asBoolean()).isTrue()
		assertThat(offered.path("result").asString()).isEqualTo("offered")
	}

	@Test
	fun `목록은 검색 상태 정렬을 지원하고 다른 사용자 데이터를 숨긴다`() {
		val userId = UUID.randomUUID()
		val anotherUserId = UUID.randomUUID()
		val first = create(userId, UUID.randomUUID(), "카카오", "서버 개발자", "applied")
		create(userId, UUID.randomUUID(), "라인", "데이터 엔지니어", "screening")

		mockMvc.perform(
			get("/api/v1/applications")
				.header(USER_HEADER, userId)
				.param("q", "직접 추가")
				.param("status", "screening"),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].company").value("라인"))

		mockMvc.perform(
			get("/api/v1/applications/{id}", first.path("id").asString())
				.header(USER_HEADER, anotherUserId),
		).andExpect(status().isNotFound)

		mockMvc.perform(
			get("/api/v1/applications")
				.header(USER_HEADER, userId)
				.param("status", "unknown"),
		).andExpect(status().isBadRequest)
	}

	@Test
	fun `검토 완료는 한 번만 변경기록을 쌓는다`() {
		val userId = UUID.randomUUID()
		val created = create(userId, UUID.randomUUID(), "당근", "백엔드 엔지니어", "screening")
		val applicationId = UUID.fromString(created.path("id").asString())
		jdbcTemplate.update(
			"UPDATE applications SET needs_review = true, version = version + 1 WHERE user_id = ? AND id = ?",
			userId,
			applicationId,
		)

		val body = MutationBody(UUID.randomUUID(), 1)
		val serializedBody = objectMapper.writeValueAsString(body)
		mockMvc.perform(
			post("/api/v1/applications/{id}/review/complete", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(serializedBody),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.needsReview").value(false))
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.changes.length()").value(1))
			.andExpect(jsonPath("$.changes[0].description").value("확인 필요 → 확인 완료"))

		mockMvc.perform(
			post("/api/v1/applications/{id}/review/complete", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(serializedBody),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.changes.length()").value(1))
	}

	private fun create(
		userId: UUID,
		mutationId: UUID,
		company: String,
		position: String,
		stage: String,
	): JsonNode {
		val response = mockMvc.perform(
			post("/api/v1/applications")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(CreateBody(mutationId, company, position, stage))),
		).andExpect(status().isCreated)
			.andReturn().response.contentAsString
		return json(response)
	}

	private fun updateStatus(
		userId: UUID,
		applicationId: String,
		mutationId: UUID,
		expectedVersion: Long,
		statusValue: String,
	): JsonNode {
		val response = mockMvc.perform(
			post("/api/v1/applications/{id}/status", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(StatusBody(mutationId, expectedVersion, statusValue))),
		).andExpect(status().isOk)
			.andReturn().response.contentAsString
		return json(response)
	}

	private fun json(value: String): JsonNode = objectMapper.readTree(value)

	data class CreateBody(val mutationId: UUID, val company: String, val position: String, val stage: String)
	data class DetailsBody(
		val mutationId: UUID,
		val expectedVersion: Long,
		val company: String,
		val position: String,
		val location: String?,
		val employmentType: String?,
	)
	data class MemoBody(val mutationId: UUID, val expectedVersion: Long, val memo: String)
	data class StatusBody(val mutationId: UUID, val expectedVersion: Long, val status: String)
	data class MutationBody(val mutationId: UUID, val expectedVersion: Long)

	@TestConfiguration(proxyBeanMethods = false)
	class FixedClockConfiguration {
		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
	}

	companion object {
		private const val USER_HEADER = "X-Jobvis-User-Id"
		private val NOW: Instant = Instant.parse("2026-08-16T15:30:00Z")
	}
}
