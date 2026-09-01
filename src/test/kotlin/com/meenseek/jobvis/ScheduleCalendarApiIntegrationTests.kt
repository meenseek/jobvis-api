package com.meenseek.jobvis

import com.meenseek.jobvis.calendar.GoogleCalendarClient
import com.meenseek.jobvis.calendar.CalendarExportService
import com.meenseek.jobvis.calendar.CreateCalendarPreviewRequest
import com.meenseek.jobvis.calendar.ConfirmCalendarExportRequest
import com.meenseek.jobvis.calendar.CalendarProviderException
import com.meenseek.jobvis.calendar.calendarClaimDuration
import com.meenseek.jobvis.common.ServiceUnavailableException
import com.meenseek.jobvis.connection.ConnectionProvider
import com.meenseek.jobvis.connection.ConnectionService
import com.meenseek.jobvis.connection.OAuthConnectionTokens
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@SpringBootTest(
	properties = [
		"jobvis.crypto.key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"jobvis.import.poll-delay=PT24H",
		"jobvis.import.cleanup-delay=PT24H",
	],
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ScheduleCalendarApiIntegrationTests.CalendarTestConfiguration::class)
class ScheduleCalendarApiIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val objectMapper: ObjectMapper,
	private val connectionService: ConnectionService,
	private val calendarCalls: AtomicInteger,
	private val calendarObservation: CalendarObservation,
	private val calendarExportService: CalendarExportService,
	private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {
	@Test
	fun `캘린더 확인 claim은 토큰 갱신과 일정 등록 제한 시간을 모두 포함한다`() {
		assertThat(calendarClaimDuration(Duration.ofSeconds(5), Duration.ofSeconds(30)))
			.isEqualTo(Duration.ofSeconds(80))
	}

	@Test
	fun `캘린더 조회는 새 로컬 사용자를 준비한 뒤 빈 일정을 반환한다`() {
		val userId = UUID.randomUUID()

		mockMvc.perform(
			get("/api/v1/calendar/schedules")
				.header(USER_HEADER, userId)
				.param("from", "2026-08-01")
				.param("to", "2026-08-31"),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.items").isEmpty)

		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM users WHERE id = ?",
				Long::class.java,
				userId,
			),
		).isEqualTo(1)
	}

	@Test
	fun `단일 일정을 수정하고 확인한 미리보기만 Google Calendar에 멱등적으로 반영한다`() {
		calendarCalls.set(0)
		val userId = UUID.randomUUID()
		val application = createApplication(userId)
		val applicationId = application.path("id").asString()
		val schedule = json(
			mockMvc.perform(
				get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId),
			).andExpect(status().isOk)
				.andExpect(jsonPath("$.version").value(0))
				.andReturn().response.contentAsString,
		)
		val scheduleId = schedule.path("id").asString()
		val mutationId = UUID.randomUUID()
		val updateBody = objectMapper.writeValueAsString(
			mapOf(
				"mutationId" to mutationId,
				"expectedVersion" to 0,
				"expectedScheduleVersion" to 0,
				"scheduleType" to "interview",
				"action" to "2차 면접",
				"scheduledAt" to "2026-08-20T05:00:00Z",
				"endsAt" to "2026-08-20T06:30:00Z",
				"timezone" to "Asia/Seoul",
				"location" to "서울 강남구",
				"description" to "화상 링크 확인",
			),
		)
		repeat(2) {
			mockMvc.perform(
				put("/api/v1/applications/{id}/schedule", applicationId)
					.header(USER_HEADER, userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(updateBody),
			).andExpect(status().isOk)
				.andExpect(jsonPath("$.id").value(scheduleId))
				.andExpect(jsonPath("$.applicationVersion").value(1))
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.action").value("2차 면접"))
		}
		val reusedMutationBody = objectMapper.writeValueAsString(
			mapOf(
				"mutationId" to mutationId,
				"expectedVersion" to 999,
				"expectedScheduleVersion" to 0,
				"scheduleType" to "interview",
				"action" to "2차 면접",
				"scheduledAt" to "2026-08-20T05:00:00Z",
				"endsAt" to "2026-08-20T06:30:00Z",
				"timezone" to "Asia/Seoul",
				"location" to "서울 강남구",
				"description" to "화상 링크 확인",
			),
		)
		mockMvc.perform(
			put("/api/v1/applications/{id}/schedule", applicationId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(reusedMutationBody),
		).andExpect(status().isConflict)
		mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.version").value(1))
		mockMvc.perform(get("/api/v1/applications/{id}/changes", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items[?(@.title == '일정 종류')]").exists())
			.andExpect(jsonPath("$.items[?(@.title == '일정 장소')]").exists())

		val connection = connectionService.upsertOAuth(
			userId,
			ConnectionProvider.GOOGLE_CALENDAR,
			OAuthConnectionTokens(
				"calendar-access-token",
				"calendar-refresh-token",
				NOW.plusSeconds(3600),
				setOf("https://www.googleapis.com/auth/calendar.events"),
				"calendar-${userId}@example.com",
			),
			ongoingSyncConsent = false,
		)
		val previewBody = objectMapper.writeValueAsString(
			mapOf(
				"scheduleId" to scheduleId,
				"connectionId" to connection.id,
				"idempotencyKey" to UUID.randomUUID(),
				"expectedScheduleVersion" to 1,
			),
		)
		val preview = json(
			mockMvc.perform(
				post("/api/v1/calendar-exports/previews")
					.header(USER_HEADER, userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(previewBody),
			).andExpect(status().isCreated)
				.andExpect(jsonPath("$.status").value("previewed"))
				.andExpect(jsonPath("$.title").value("테스트 회사 · 2차 면접"))
				.andReturn().response.contentAsString,
		)
		val exportId = preview.path("id").asString()
		val previewHash = preview.path("previewHash").asString()

		mockMvc.perform(
			post("/api/v1/calendar-exports/{id}/confirm", exportId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("previewHash" to "not-the-preview"))),
		).andExpect(status().isConflict)

		val confirmBody = objectMapper.writeValueAsString(mapOf("previewHash" to previewHash))
		repeat(2) {
			mockMvc.perform(
				post("/api/v1/calendar-exports/{id}/confirm", exportId)
					.header(USER_HEADER, userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(confirmBody),
			).andExpect(status().isOk)
				.andExpect(jsonPath("$.status").value("confirmed"))
					.andExpect(jsonPath("$.providerEventId").value(org.hamcrest.Matchers.startsWith("provider-jobvis")))
		}
		assertThat(calendarCalls.get()).isEqualTo(1)
		assertThat(calendarObservation.status.get()).isEqualTo("CONFIRMING")

		mockMvc.perform(get("/api/v1/calendar-exports/{id}", exportId).header(USER_HEADER, UUID.randomUUID()))
			.andExpect(status().isNotFound)
	}

	@Test
	fun `동시 캘린더 미리보기는 같은 idempotency 결과를 반환한다`() {
		val userId = UUID.randomUUID()
		val application = createApplication(userId)
		val applicationId = application.path("id").asString()
		val schedule = json(
			mockMvc.perform(get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId))
				.andExpect(status().isOk).andReturn().response.contentAsString,
		)
		val connection = connectionService.upsertOAuth(
			userId,
			ConnectionProvider.GOOGLE_CALENDAR,
			OAuthConnectionTokens(
				"calendar-access-token", "calendar-refresh-token", NOW.plusSeconds(3600),
				setOf("https://www.googleapis.com/auth/calendar.events"), "calendar-concurrent@example.com",
			),
			false,
		)
		val request = CreateCalendarPreviewRequest(
			UUID.fromString(schedule.path("id").asString()), connection.id, UUID.randomUUID(), 0,
		)
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val futures = (1..2).map {
				executor.submit<java.util.UUID> {
					start.await()
					calendarExportService.preview(userId, request).id
				}
			}
			start.countDown()
			assertThat(futures.map { it.get() }.toSet()).hasSize(1)
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `같은 일정 PUT은 지원과 일정 버전을 증가시키지 않는다`() {
		val userId = UUID.randomUUID()
		val application = createApplication(userId)
		val applicationId = application.path("id").asString()
		val schedule = json(
			mockMvc.perform(get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId))
				.andExpect(status().isOk).andReturn().response.contentAsString,
		)
		val body = objectMapper.writeValueAsString(
			mapOf(
				"mutationId" to UUID.randomUUID(),
				"expectedVersion" to 0,
				"expectedScheduleVersion" to 0,
				"scheduleType" to schedule.path("scheduleType").asString(),
				"action" to schedule.path("action").asString(),
				"scheduledAt" to schedule.path("scheduledAt").asString(),
				"endsAt" to schedule.path("endsAt").asString(),
				"timezone" to schedule.path("timezone").asString(),
				"location" to schedule.path("location").asString(),
				"description" to schedule.path("description").asString(),
			),
		)
		mockMvc.perform(
			put("/api/v1/applications/{id}/schedule", applicationId)
				.header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON).content(body),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.applicationVersion").value(0))
			.andExpect(jsonPath("$.version").value(0))
	}

	@Test
	fun `같은 스냅샷의 서로 다른 idempotencyKey는 각자 바인딩되지만 공급자 이벤트 ID는 같다`() {
		val userId = UUID.randomUUID()
		val application = createApplication(userId)
		val schedule = json(
			mockMvc.perform(
				get("/api/v1/applications/{id}/schedule", application.path("id").asString()).header(USER_HEADER, userId),
			).andExpect(status().isOk).andReturn().response.contentAsString,
		)
		val connection = connectionService.upsertOAuth(
			userId,
			ConnectionProvider.GOOGLE_CALENDAR,
			OAuthConnectionTokens(
				"calendar-access-token", "calendar-refresh-token", NOW.plusSeconds(3600),
				setOf("https://www.googleapis.com/auth/calendar.events"), "calendar-two-keys@example.com",
			),
			false,
		)
		val scheduleId = UUID.fromString(schedule.path("id").asString())
		val first = calendarExportService.preview(
			userId, CreateCalendarPreviewRequest(scheduleId, connection.id, UUID.randomUUID(), 0),
		)
		val second = calendarExportService.preview(
			userId, CreateCalendarPreviewRequest(scheduleId, connection.id, UUID.randomUUID(), 0),
		)
		assertThat(first.id).isNotEqualTo(second.id)
		val firstConfirmed = calendarExportService.confirm(
			userId, first.id, ConfirmCalendarExportRequest(first.previewHash),
		)
		val secondConfirmed = calendarExportService.confirm(
			userId, second.id, ConfirmCalendarExportRequest(second.previewHash),
		)
		assertThat(firstConfirmed.providerEventId).isEqualTo(secondConfirmed.providerEventId)
		assertThat(calendarObservation.providerIds.takeLast(2).distinct()).hasSize(1)
	}

	@Test
	fun `일시적 Calendar 확인 실패는 claim을 정리해 즉시 재시도할 수 있다`() {
		val userId = UUID.randomUUID()
		val application = createApplication(userId)
		val schedule = json(
			mockMvc.perform(
				get("/api/v1/applications/{id}/schedule", application.path("id").asString())
					.header(USER_HEADER, userId),
			).andExpect(status().isOk).andReturn().response.contentAsString,
		)
		val connection = connectionService.upsertOAuth(
			userId,
			ConnectionProvider.GOOGLE_CALENDAR,
			OAuthConnectionTokens(
				"calendar-access-token", "calendar-refresh-token", NOW.plusSeconds(3600),
				setOf("https://www.googleapis.com/auth/calendar.events"), "calendar-retry@example.com",
			),
			false,
		)
		val preview = calendarExportService.preview(
			userId,
			CreateCalendarPreviewRequest(
				UUID.fromString(schedule.path("id").asString()), connection.id, UUID.randomUUID(), 0,
			),
		)
		val gate = calendarObservation.armTemporaryFailure()
		gate.release.countDown()
		try {
			assertThatThrownBy {
				calendarExportService.confirm(userId, preview.id, ConfirmCalendarExportRequest(preview.previewHash))
			}.isInstanceOf(ServiceUnavailableException::class.java)
			val failed = jdbcTemplate.queryForMap(
				"SELECT status, last_error_code, claim_token FROM calendar_exports WHERE id = ?", preview.id,
			)
			assertThat(failed["status"]).isEqualTo("FAILED")
			assertThat(failed["last_error_code"]).isEqualTo("CALENDAR_CREDENTIAL_TEMPORARILY_UNAVAILABLE")
			assertThat(failed["claim_token"]).isNull()
		} finally {
			calendarObservation.clearFailure()
		}

		val confirmed = calendarExportService.confirm(
			userId, preview.id, ConfirmCalendarExportRequest(preview.previewHash),
		)
		assertThat(confirmed.status).isEqualTo("confirmed")
	}

	@Test
	fun `이전 Calendar 토큰의 늦은 인증 오류는 새로 연결한 토큰을 재승인 상태로 바꾸지 않는다`() {
		val userId = UUID.randomUUID()
		val application = createApplication(userId)
		val applicationId = application.path("id").asString()
		val schedule = json(
			mockMvc.perform(get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId))
				.andExpect(status().isOk).andReturn().response.contentAsString,
		)
		val update = objectMapper.writeValueAsString(
			mapOf(
				"mutationId" to UUID.randomUUID(), "expectedVersion" to 0, "expectedScheduleVersion" to 0,
				"scheduleType" to "interview", "action" to "면접",
				"scheduledAt" to "2026-08-20T05:00:00Z", "endsAt" to "2026-08-20T06:00:00Z",
				"timezone" to "Asia/Seoul", "location" to "", "description" to "",
			),
		)
		mockMvc.perform(
			put("/api/v1/applications/{id}/schedule", applicationId)
				.header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON).content(update),
		).andExpect(status().isOk)
		val accountEmail = "calendar-race@example.com"
		val connection = connectionService.upsertOAuth(
			userId, ConnectionProvider.GOOGLE_CALENDAR,
			OAuthConnectionTokens(
				"old-token", "old-refresh", NOW.plusSeconds(3600),
				setOf("https://www.googleapis.com/auth/calendar.events"), accountEmail,
			), false,
		)
		val preview = calendarExportService.preview(
			userId,
			CreateCalendarPreviewRequest(
				UUID.fromString(schedule.path("id").asString()), connection.id, UUID.randomUUID(), 1,
			),
		)
		val gate = calendarObservation.armReauthorizationFailure()
		val executor = Executors.newSingleThreadExecutor()
		try {
			val future = executor.submit {
				calendarExportService.confirm(userId, preview.id, ConfirmCalendarExportRequest(preview.previewHash))
			}
			check(gate.started.await(10, TimeUnit.SECONDS))
			connectionService.upsertOAuth(
				userId, ConnectionProvider.GOOGLE_CALENDAR,
				OAuthConnectionTokens(
					"new-token", "new-refresh", NOW.plusSeconds(7200),
					setOf("https://www.googleapis.com/auth/calendar.events"), accountEmail,
				), false,
			)
			gate.release.countDown()
			assertThatThrownBy { future.get(10, TimeUnit.SECONDS) }
				.hasCauseInstanceOf(ServiceUnavailableException::class.java)
			val stored = jdbcTemplate.queryForMap(
				"SELECT status, version FROM external_connections WHERE id = ?", connection.id,
			)
			assertThat(stored["status"]).isEqualTo("CONNECTED")
			assertThat((stored["version"] as Number).toLong()).isEqualTo(1)
		} finally {
			gate.release.countDown()
			calendarObservation.clearFailure()
			executor.shutdownNow()
		}
	}

	private fun createApplication(userId: UUID): JsonNode {
		val response = mockMvc.perform(
			post("/api/v1/applications")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"mutationId" to UUID.randomUUID(),
							"company" to "테스트 회사",
							"position" to "백엔드 엔지니어",
							"status" to "applied",
						),
					),
				),
		).andExpect(status().isCreated).andReturn().response.contentAsString
		val application = json(response)
		jdbcTemplate.update(
			"""
				INSERT INTO application_schedules (
				    id, user_id, application_id, schedule_type, action,
				    all_day, scheduled_date, scheduled_at, ends_at, timezone,
				    location, description, last_import_received_at, manually_edited,
				    completed, completed_at, version, created_at, updated_at
				) VALUES (?, ?, ?, 'OTHER', '후속 일정', false, NULL, ?, ?, 'Asia/Seoul',
				          '', '', NULL, true, false, NULL, 0, ?, ?)
			""".trimIndent(),
			UUID.randomUUID(),
			userId,
			UUID.fromString(application.path("id").asString()),
			Timestamp.from(Instant.parse("2026-08-19T05:00:00Z")),
			Timestamp.from(Instant.parse("2026-08-19T06:00:00Z")),
			Timestamp.from(NOW),
			Timestamp.from(NOW),
		)
		return application
	}

	private fun json(value: String): JsonNode = objectMapper.readTree(value)

	@TestConfiguration
	class CalendarTestConfiguration {
		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

		@Bean
		fun calendarCalls(): AtomicInteger = AtomicInteger()

		@Bean
		fun calendarObservation(): CalendarObservation = CalendarObservation()

		@Bean
		@Primary
		fun googleCalendarClient(
			calendarCalls: AtomicInteger,
			calendarObservation: CalendarObservation,
			jdbcTemplate: JdbcTemplate,
			): GoogleCalendarClient = GoogleCalendarClient { _, event ->
				calendarCalls.incrementAndGet()
				calendarObservation.providerIds += event.providerEventId
				calendarObservation.failureGate?.let { gate ->
					gate.started.countDown()
					check(gate.release.await(10, TimeUnit.SECONDS))
					throw gate.failure
				}
			calendarObservation.status.set(
				jdbcTemplate.queryForObject(
					"SELECT status FROM calendar_exports WHERE status = 'CONFIRMING' LIMIT 1", String::class.java,
				),
			)
			"provider-${event.providerEventId}"
		}
	}

	class CalendarObservation {
		val status: AtomicReference<String?> = AtomicReference()
		val providerIds: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
		@Volatile
		var failureGate: CalendarFailureGate? = null

		fun armReauthorizationFailure(): CalendarFailureGate = CalendarFailureGate(
			CalendarProviderException("GOOGLE_CALENDAR_REAUTHORIZATION_REQUIRED", true),
		).also { failureGate = it }
		fun armTemporaryFailure(): CalendarFailureGate = CalendarFailureGate(
			ServiceUnavailableException("외부 서비스 토큰을 일시적으로 갱신할 수 없습니다."),
		).also { failureGate = it }
		fun clearFailure() {
			failureGate = null
		}
	}

	class CalendarFailureGate(val failure: RuntimeException) {
		val started = CountDownLatch(1)
		val release = CountDownLatch(1)
	}

	private companion object {
		const val USER_HEADER = "X-Jobvis-User-Id"
		val NOW: Instant = Instant.parse("2026-08-17T00:00:00Z")
	}
}
