package com.meenseek.jobvis

import com.meenseek.jobvis.connection.ConnectionProvider
import com.meenseek.jobvis.connection.ExternalConnectionRepository
import com.meenseek.jobvis.connection.ExternalConnection
import com.meenseek.jobvis.connection.NaverCredentialValidator
import com.meenseek.jobvis.imports.ImportRunRepository
import com.meenseek.jobvis.imports.ImportRunWorker
import com.meenseek.jobvis.imports.MailCandidate
import com.meenseek.jobvis.imports.MailCollector
import com.meenseek.jobvis.imports.MailCollectionResult
import com.meenseek.jobvis.imports.MonitoringImportScheduler
import com.meenseek.jobvis.imports.ImportRunClaimService
import com.meenseek.jobvis.imports.ImportRunCompletionService
import com.meenseek.jobvis.imports.ImportRunService
import com.meenseek.jobvis.imports.CreateImportRunRequest
import com.meenseek.jobvis.imports.MailFailureDisposition
import com.meenseek.jobvis.common.ServiceUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
	properties = [
		"jobvis.crypto.key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"jobvis.import.poll-delay=PT24H",
		"jobvis.import.cleanup-delay=PT24H",
		"jobvis.import.monitor-delay=PT24H",
	],
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ImportApiIntegrationTests.ImportTestConfiguration::class)
class ImportApiIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val objectMapper: ObjectMapper,
	private val jdbcTemplate: JdbcTemplate,
	private val worker: ImportRunWorker,
	private val monitoringScheduler: MonitoringImportScheduler,
	private val claimService: ImportRunClaimService,
	private val completionService: ImportRunCompletionService,
	private val runRepository: ImportRunRepository,
	private val connectionRepository: ExternalConnectionRepository,
	private val runService: ImportRunService,
	private val testMailCollector: TestMailCollector,
) : PostgresIntegrationTest() {
	@BeforeEach
	fun resetStoredTestState() {
		jdbcTemplate.execute("TRUNCATE TABLE users CASCADE")
	}

	@Test
	fun `scheduled dispatch는 외부 메일 수집 완료를 기다리지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		val run = createRun(userId, connectionId)
		val gate = testMailCollector.block(connectionId)
		val dispatcher = Executors.newSingleThreadExecutor()
		try {
			val dispatched = dispatcher.submit<Boolean> { worker.dispatchOnce() }
			assertThat(gate.started.await(10, TimeUnit.SECONDS)).isTrue()
			assertThat(dispatched.get(1, TimeUnit.SECONDS)).isTrue()
		} finally {
			gate.release.countDown()
			dispatcher.shutdownNow()
		}
		awaitRunStatus(UUID.fromString(run.path("id").asString()), "COMPLETED")
	}

	@Test
	fun `같은 Gmail 계정의 동시 실행은 사용자와 인스턴스가 달라도 하나만 생성한다`() {
		val firstUserId = UUID.randomUUID()
		val secondUserId = UUID.randomUUID()
		val firstConnectionId = connectGmail(firstUserId, "shared-account@example.com")
		val secondConnectionId = connectGmail(secondUserId, "SHARED-ACCOUNT@example.com")
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val futures = listOf(
				firstUserId to firstConnectionId,
				secondUserId to secondConnectionId,
			).map { (userId, connectionId) ->
				executor.submit(
					java.util.concurrent.Callable {
						start.await()
						runCatching {
							runService.create(
								userId,
							CreateImportRunRequest(
								UUID.randomUUID(),
								connectionId,
									LocalDate.parse("2026-08-01"),
									LocalDate.parse("2026-08-17"),
								),
							)
						}
					},
				)
			}
			start.countDown()
			val results = futures.map { it.get(10, TimeUnit.SECONDS) }

			assertThat(results.count { it.isSuccess }).isEqualTo(1)
			assertThat(results.count { it.isFailure }).isEqualTo(1)
			assertThat(
				jdbcTemplate.queryForObject(
					"""
						SELECT count(*) FROM import_runs
						WHERE connection_id IN (?, ?) AND status IN ('QUEUED', 'RUNNING')
					""".trimIndent(),
					Long::class.java,
					firstConnectionId,
					secondConnectionId,
				),
			).isEqualTo(1)
		} finally {
			start.countDown()
			executor.shutdownNow()
			jdbcTemplate.update(
				"""
					UPDATE import_runs
					SET status = 'CANCELLED', completed_at = ?, updated_at = ?
					WHERE connection_id IN (?, ?) AND status IN ('QUEUED', 'RUNNING')
				""".trimIndent(),
				java.sql.Timestamp.from(NOW),
				java.sql.Timestamp.from(NOW),
				firstConnectionId,
				secondConnectionId,
			)
		}
	}

	@Test
	fun `interrupt를 무시하는 실행은 종료 중 재큐잉하지 않고 lease 회수에 맡긴다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		val runId = UUID.fromString(createRun(userId, connectionId).path("id").asString())
		val started = CountDownLatch(1)
		val release = CountDownLatch(1)
		val finished = CountDownLatch(1)
		val interrupted = AtomicInteger()
		val interruptIgnoringCollector = MailCollector { connection, _, _ ->
			started.countDown()
			try {
				var released = false
				while (!released) {
					try {
						released = release.await(10, TimeUnit.SECONDS)
					} catch (_: InterruptedException) {
						interrupted.incrementAndGet()
					}
				}
				MailCollectionResult(emptyList(), connection.version)
			} finally {
				finished.countDown()
			}
		}
		val isolatedWorker = ImportRunWorker(
			claimService, runRepository, connectionRepository, interruptIgnoringCollector, completionService,
			1, Duration.ZERO, Duration.ofSeconds(1), 10,
		)
		try {
			assertThat(isolatedWorker.dispatchOnce()).isTrue()
			assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
			isolatedWorker.shutdownExecutors()
			assertThat(interrupted.get()).isGreaterThan(0)
			awaitRunStatus(runId, "RUNNING")
			release.countDown()
			assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue()
			awaitRunStatus(runId, "RUNNING")
		} finally {
			release.countDown()
			isolatedWorker.shutdownExecutors()
			jdbcTemplate.update(
				"""
					UPDATE import_runs
					SET status = 'CANCELLED', completed_at = ?, updated_at = ?,
					    lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL
					WHERE id = ? AND status = 'RUNNING'
				""".trimIndent(),
				java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), runId,
			)
		}
	}

	@Test
	fun `읽기 전용 가져오기는 채용 메일을 지원과 일정으로 직접 확정한다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		val run = createRun(userId, connectionId)

		assertThat(applicationCount(userId)).isZero()
		processRun(run)

		mockMvc.perform(get("/api/v1/import-runs/{id}", run.path("id").asString()).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.status").value("completed"))
			.andExpect(jsonPath("$.scannedCount").value(2))
			.andExpect(jsonPath("$.finalizedCount").value(1))
			.andExpect(jsonPath("$.ignoredCount").value(1))
			.andExpect(jsonPath("$.duplicateCount").value(0))
		assertThat(applicationCount(userId)).isEqualTo(1)
		assertThat(storedRawMailColumns()).isEmpty()

		val applicationId = jdbcTemplate.queryForObject(
			"SELECT id FROM applications WHERE user_id = ?", UUID::class.java, userId,
		) ?: error("자동 확정된 지원이 없습니다.")
		mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.company").value("Acme"))
			.andExpect(jsonPath("$.needsReview").value(true))
		mockMvc.perform(get("/api/v1/applications/{id}/emails", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(1))
		mockMvc.perform(get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.scheduleType").value("interview"))
			.andExpect(jsonPath("$.scheduledAt").value("2026-08-20T05:00:00Z"))
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM application_schedules WHERE user_id = ?",
				Long::class.java,
				userId,
			),
		).isEqualTo(1)

		assertThat(
			jdbcTemplate.queryForList(
				"SELECT state FROM mail_ingestion_ledger WHERE user_id = ? ORDER BY state",
				String::class.java, userId,
			),
		).containsExactly("FINALIZED", "IGNORED")
		mockMvc.perform(get("/api/v1/import-drafts").header(USER_HEADER, userId))
			.andExpect(status().isNotFound)
	}

	@Test
	fun `같은 연결의 활성 실행은 거부하고 공개 draft API는 존재하지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		createRun(userId, connectionId)
		mockMvc.perform(
			post("/api/v1/import-runs")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(importRunBody(connectionId)),
		).andExpect(status().isConflict)

		assertThat(worker.runOnce()).isTrue()
		assertThat(applicationCount(userId)).isEqualTo(1)
		mockMvc.perform(post("/api/v1/import-drafts/{id}/reject", UUID.randomUUID()).header(USER_HEADER, userId))
			.andExpect(status().isNotFound)
	}

	@Test
	fun `자동 확인 동의 연결만 최근 구간을 모니터 실행으로 큐잉한다`() {
		val userId = UUID.randomUUID()
		connectNaver(userId, ongoingSyncConsent = true)

		assertThat(monitoringScheduler.enqueueDue()).isEqualTo(1)
		mockMvc.perform(get("/api/v1/import-runs").header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].requestedBy").value("monitor"))
			.andExpect(jsonPath("$.items[0].dateFrom").value("2026-08-10"))
			.andExpect(jsonPath("$.items[0].dateTo").value("2026-08-17"))

		assertThat(worker.runOnce()).isTrue()
		mockMvc.perform(get("/api/v1/import-runs").header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items[0].status").value("completed"))
	}

	@Test
	fun `여러 인스턴스의 동시 모니터 큐잉은 하나만 만들고 트랜잭션 오류를 남기지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId, ongoingSyncConsent = true)
		val start = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			val futures = (1..2).map {
				executor.submit<Int> {
					check(start.await(10, TimeUnit.SECONDS))
					if (runService.queueMonitor(
						userId, connectionId, java.time.LocalDate.parse("2026-08-10"),
						java.time.LocalDate.parse("2026-08-17"),
					) != null) 1 else 0
				}
			}
			start.countDown()
			assertThat(futures.sumOf { it.get(15, TimeUnit.SECONDS) }).isEqualTo(1)
			assertThat(
				jdbcTemplate.queryForObject(
					"SELECT count(*) FROM import_runs WHERE user_id = ? AND status = 'QUEUED'",
					Long::class.java,
					userId,
				),
			).isEqualTo(1)
		} finally {
			executor.shutdownNow()
		}
	}

	@Test
	fun `같은 provider process의 후속 메일은 기존 지원과 단일 일정에 원자적으로 연결한다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		processRun(createRun(userId, connectionId))
		val applicationId = jdbcTemplate.queryForObject(
			"SELECT id FROM applications WHERE user_id = ?", UUID::class.java, userId,
		) ?: error("자동 확정된 지원이 없습니다.")

		processRun(createRun(userId, connectionId))

		assertThat(applicationCount(userId)).isEqualTo(1)
		mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.status").value("offered"))
			.andExpect(jsonPath("$.needsReview").value(true))
		mockMvc.perform(get("/api/v1/applications/{id}/emails", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items.length()").value(2))
		mockMvc.perform(get("/api/v1/applications/{id}/changes", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items[0].title").value("진행 상태"))
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM application_schedules WHERE user_id = ? AND application_id = ?",
				Long::class.java, userId, applicationId,
			),
		).isEqualTo(1)
	}

	@Test
	fun `자동 확인 동의를 철회하면 대기 중 모니터 실행을 취소하고 메일을 읽지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId, ongoingSyncConsent = true)
		assertThat(monitoringScheduler.enqueueDue()).isEqualTo(1)
		mockMvc.perform(
			patch("/api/v1/connections/{id}/monitoring-consent", connectionId)
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("expectedVersion" to 0, "enabled" to false))),
		).andExpect(status().isOk)
		assertThat(worker.runOnce()).isFalse()
		assertThat(testMailCollector.calls(connectionId)).isZero()
		mockMvc.perform(get("/api/v1/import-runs").header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items[0].status").value("cancelled"))
			.andExpect(jsonPath("$.items[0].errorCode").value("MONITORING_CONSENT_REVOKED"))
	}

	@Test
	fun `claim 뒤 동의가 철회된 모니터 실행도 외부 호출 직전에 취소한다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId, ongoingSyncConsent = true)
		assertThat(monitoringScheduler.enqueueDue()).isEqualTo(1)
		val claim = claimService.claimNext() ?: error("claim이 없습니다.")
		jdbcTemplate.update(
			"UPDATE external_connections SET ongoing_sync_consent = false, next_sync_after = NULL WHERE id = ?",
			connectionId,
		)

		worker.processClaim(claim)

		assertThat(testMailCollector.calls(connectionId)).isZero()
		assertThat(
			jdbcTemplate.queryForObject("SELECT status FROM import_runs WHERE id = ?", String::class.java, claim.runId),
		).isEqualTo("CANCELLED")
	}

	@Test
	fun `대기 중 연결 세대가 바뀌면 외부 메일을 읽기 전에 실행을 취소한다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		val run = createRun(userId, connectionId)
		jdbcTemplate.update("UPDATE external_connections SET version = version + 1 WHERE id = ?", connectionId)

		processRun(run)

		assertThat(testMailCollector.calls(connectionId)).isZero()
		val stored = jdbcTemplate.queryForMap(
			"SELECT status, error_code FROM import_runs WHERE id = ?",
			UUID.fromString(run.path("id").asString()),
		)
		assertThat(stored["status"]).isEqualTo("CANCELLED")
		assertThat(stored["error_code"]).isEqualTo("CONNECTION_CHANGED")
	}

	@Test
	fun `수집 뒤 연결 세대가 바뀌면 늦은 메시지를 확정하지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		createRun(userId, connectionId)
		val claim = claimService.claimNext() ?: error("claim이 없습니다.")
		val collectedVersion = connectionVersion(connectionId)
		jdbcTemplate.update("UPDATE external_connections SET version = version + 1 WHERE id = ?", connectionId)

		completionService.complete(
			claim,
			MailCollectionResult(
				listOf(
					MailCandidate(
						ConnectionProvider.NAVER, "late-generation-message", "[Acme] 면접 안내",
						"recruit@acme.example", NOW, "2026-08-20 14:00 면접입니다.",
					),
				),
				collectedVersion,
			),
		)

		val stored = jdbcTemplate.queryForMap(
			"SELECT status, error_code FROM import_runs WHERE id = ?", claim.runId,
		)
		assertThat(stored["status"]).isEqualTo("CANCELLED")
		assertThat(stored["error_code"]).isEqualTo("CONNECTION_CHANGED")
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM applications WHERE user_id = ?", Long::class.java, userId,
			),
		).isZero()
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM mail_ingestion_ledger WHERE user_id = ?", Long::class.java, userId,
			),
		).isZero()
	}

	@Test
	fun `연결을 해제하면 같은 트랜잭션에서 활성 가져오기를 취소한다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		val run = createRun(userId, connectionId)

		mockMvc.perform(
			org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
				"/api/v1/connections/{id}", connectionId,
			).header(USER_HEADER, userId),
		).andExpect(status().isNoContent)

		val stored = jdbcTemplate.queryForMap(
			"SELECT status, error_code FROM import_runs WHERE id = ?",
			UUID.fromString(run.path("id").asString()),
		)
		assertThat(stored["status"]).isEqualTo("CANCELLED")
		assertThat(stored["error_code"]).isEqualTo("CONNECTION_REVOKED")
	}

	@Test
	fun `만료된 lease는 새 소유자가 회수하고 이전 소유자는 완료할 수 없다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		val run = createRun(userId, connectionId)
		val first = claimService.claimNext() ?: error("첫 claim이 없습니다.")
		jdbcTemplate.update(
			"UPDATE import_runs SET lease_expires_at = ? WHERE id = ?",
			java.sql.Timestamp.from(NOW.minusSeconds(1)), first.runId,
		)
		val second = claimService.claimNext() ?: error("재회수 claim이 없습니다.")
		assertThat(second.runId.toString()).isEqualTo(run.path("id").asString())
		assertThat(second.leaseOwner).isNotEqualTo(first.leaseOwner)

		completionService.complete(first, MailCollectionResult(emptyList(), connectionVersion(connectionId)))
		assertThat(
			jdbcTemplate.queryForObject("SELECT status FROM import_runs WHERE id = ?", String::class.java, first.runId),
		).isEqualTo("RUNNING")
		completionService.complete(second, MailCollectionResult(emptyList(), connectionVersion(connectionId)))
		assertThat(
			jdbcTemplate.queryForObject("SELECT status FROM import_runs WHERE id = ?", String::class.java, first.runId),
		).isEqualTo("COMPLETED")
	}

	@Test
	fun `claim lease는 JVM clock이 아니라 DB clock에서 2분을 계산한다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		createRun(userId, connectionId)
		val claim = claimService.claimNext() ?: error("claim이 없습니다.")

		val remainingSeconds = requireNotNull(
			jdbcTemplate.queryForObject(
				"""
					SELECT EXTRACT(EPOCH FROM (lease_expires_at - clock_timestamp()))::double precision
					FROM import_runs
					WHERE id = ?
				""".trimIndent(),
				Double::class.java,
				claim.runId,
			),
		)
		assertThat(remainingSeconds).isBetween(110.0, 120.0)
	}

	@Test
	fun `이미 claim된 실행은 늦은 취소 요청이 RUNNING 상태를 덮어쓰지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		val run = createRun(userId, connectionId)
		val claim = claimService.claimNext() ?: error("claim이 없습니다.")
		mockMvc.perform(
			post("/api/v1/import-runs/{id}/cancel", run.path("id").asString()).header(USER_HEADER, userId),
		).andExpect(status().isConflict)
		assertThat(
			jdbcTemplate.queryForObject("SELECT status FROM import_runs WHERE id = ?", String::class.java, claim.runId),
		).isEqualTo("RUNNING")
	}

	@Test
	fun `일시적인 메일 장애는 연결을 유지하고 다음 자동 재시도를 예약한다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId, ongoingSyncConsent = true)
		createRun(userId, connectionId)
		val claim = claimService.claimNext() ?: error("claim이 없습니다.")
		completionService.fail(claim, "MAIL_PROVIDER_BUSY", MailFailureDisposition.TRANSIENT)

		val connection = jdbcTemplate.queryForMap(
			"SELECT status, next_sync_after, last_error_code FROM external_connections WHERE id = ?",
			connectionId,
		)
		assertThat(connection["status"]).isEqualTo("CONNECTED")
		assertThat(connection["next_sync_after"]).isNotNull()
		assertThat(connection["last_error_code"]).isEqualTo("MAIL_PROVIDER_BUSY")
	}

	@Test
	fun `이전 자격증명으로 발생한 늦은 인증 오류는 새로 연결한 자격증명을 무효화하지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		val run = createRun(userId, connectionId)
		val targetRunId = UUID.fromString(run.path("id").asString())
		var targetClaim: com.meenseek.jobvis.imports.ClaimedImportRun? = null
		repeat(20) {
			val next = claimService.claimNext() ?: return@repeat
			if (next.runId == targetRunId) {
				targetClaim = next
				return@repeat
			}
			completionService.cancel(next, "TEST_QUEUE_DRAIN")
		}
		val claim = targetClaim ?: error("대상 claim이 없습니다.")
		val reconnectedId = connectNaver(userId)
		assertThat(reconnectedId).isEqualTo(connectionId)

		completionService.fail(
			claim, "LATE_AUTH_FAILURE", MailFailureDisposition.REAUTHORIZATION_REQUIRED, expectedConnectionVersion = 0,
		)

		val connection = jdbcTemplate.queryForMap(
			"SELECT status, version, last_error_code FROM external_connections WHERE id = ?", connectionId,
		)
		assertThat(connection["status"]).isEqualTo("CONNECTED")
		assertThat((connection["version"] as Number).toLong()).isEqualTo(1)
		assertThat(connection["last_error_code"]).isNull()
		val storedRun = jdbcTemplate.queryForMap(
			"SELECT status, error_code FROM import_runs WHERE id = ?", claim.runId,
		)
		assertThat(storedRun["status"]).isEqualTo("CANCELLED")
		assertThat(storedRun["error_code"]).isEqualTo("CONNECTION_RECONNECTED")
	}

	@Test
	fun `실행 범위와 메시지 처리 오류도 정상 연결을 비활성화하지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId, ongoingSyncConsent = true)
		val originalNextSync = jdbcTemplate.queryForObject(
			"SELECT next_sync_after FROM external_connections WHERE id = ?", java.sql.Timestamp::class.java, connectionId,
		)
		createRun(userId, connectionId)
		val claim = claimService.claimNext() ?: error("claim이 없습니다.")
		completionService.fail(claim, "IMPORT_LIMIT_EXCEEDED", MailFailureDisposition.RUN_ONLY)
		val connection = jdbcTemplate.queryForMap(
			"SELECT status, next_sync_after, last_error_code FROM external_connections WHERE id = ?",
			connectionId,
		)
		assertThat(connection["status"]).isEqualTo("CONNECTED")
		assertThat(connection["next_sync_after"]).isEqualTo(originalNextSync)
		assertThat(connection["last_error_code"]).isNull()
		jdbcTemplate.update(
			"UPDATE external_connections SET ongoing_sync_consent = false, next_sync_after = NULL WHERE id = ?",
			connectionId,
		)
	}

	@Test
	fun `모니터 실행 중단은 API에 보이고 명시적으로 재개할 수 있다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId, ongoingSyncConsent = true)
		assertThat(monitoringScheduler.enqueueDue()).isGreaterThanOrEqualTo(1)
		val targetRunId = jdbcTemplate.queryForObject(
			"SELECT id FROM import_runs WHERE user_id = ? AND connection_id = ? AND status = 'QUEUED'",
			UUID::class.java, userId, connectionId,
		) ?: error("모니터 실행이 큐잉되지 않았습니다.")
		var targetClaim: com.meenseek.jobvis.imports.ClaimedImportRun? = null
		for (attempt in 0 until 50) {
			val next = claimService.claimNext() ?: continue
			if (next.runId == targetRunId) {
				targetClaim = next
				break
			}
			completionService.cancel(next, "TEST_QUEUE_DRAIN")
		}
		val claim = targetClaim ?: error("대상 모니터 claim이 없습니다.")
		completionService.fail(claim, "IMPORT_LIMIT_EXCEEDED", MailFailureDisposition.RUN_ONLY, 0)

		val connection = json(
			mockMvc.perform(get("/api/v1/connections").header(USER_HEADER, userId))
				.andExpect(status().isOk)
				.andExpect(jsonPath("$[0].monitoringPaused").value(true))
				.andExpect(jsonPath("$[0].lastErrorCode").value("IMPORT_LIMIT_EXCEEDED"))
				.andExpect(jsonPath("$[0].nextSyncAfter").doesNotExist())
				.andReturn().response.contentAsString,
		).get(0)

		mockMvc.perform(
			post("/api/v1/connections/{id}/monitoring/resume", connectionId)
				.header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("expectedVersion" to connection.path("version").asLong()))),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.monitoringPaused").value(false))
			.andExpect(jsonPath("$.nextSyncAfter").exists())
	}

	@Test
	fun `재승인이 필요한 연결은 모니터 재개로 해결할 수 있는 일시정지로 표시하지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId, ongoingSyncConsent = true)
		jdbcTemplate.update(
			"""
				UPDATE external_connections
				SET status = 'REAUTHORIZATION_REQUIRED', next_sync_after = NULL,
				    last_error_code = 'NAVER_REAUTHORIZATION_REQUIRED'
				WHERE id = ?
			""".trimIndent(),
			connectionId,
		)

		mockMvc.perform(get("/api/v1/connections").header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$[0].status").value("reauthorization_required"))
			.andExpect(jsonPath("$[0].monitoringPaused").value(false))
	}

	@Test
	fun `토큰 갱신의 일시 장애는 worker에서 재시도 가능한 오류로 분류한다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId, ongoingSyncConsent = true)
		testMailCollector.useServiceUnavailable(connectionId)
		createRun(userId, connectionId)
		assertThat(worker.runOnce()).isTrue()

		val connection = jdbcTemplate.queryForMap(
			"SELECT status, next_sync_after, last_error_code FROM external_connections WHERE id = ?", connectionId,
		)
		assertThat(connection["status"]).isEqualTo("CONNECTED")
		assertThat(connection["next_sync_after"]).isNotNull()
		assertThat(connection["last_error_code"]).isEqualTo("IMPORT_SERVICE_TEMPORARILY_UNAVAILABLE")
	}

	@Test
	fun `과거 수동 가져오기는 현재 시각이 아니라 실제 조회 범위까지만 동기화 지점을 전진시킨다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId, ongoingSyncConsent = true)
		val body = objectMapper.writeValueAsString(
			mapOf(
				"mutationId" to UUID.randomUUID(),
				"connectionId" to connectionId,
				"dateFrom" to "2021-01-01",
				"dateTo" to "2021-12-31",
			),
		)
		mockMvc.perform(
			post("/api/v1/import-runs").header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON).content(body),
		).andExpect(status().isAccepted)
		assertThat(worker.runOnce()).isTrue()
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT last_synced_at FROM external_connections WHERE id = ?", java.sql.Timestamp::class.java,
				connectionId,
			)?.toInstant(),
		).isEqualTo(Instant.parse("2021-12-31T15:00:00Z"))
	}

	@Test
	fun `10년보다 오래된 동기화 기준점은 허용 범위로 나누고 다른 연결도 함께 큐잉한다`() {
		val oldUserId = UUID.randomUUID()
		val recentUserId = UUID.randomUUID()
		val oldConnectionId = connectNaver(oldUserId, ongoingSyncConsent = true)
		val recentConnectionId = connectNaver(recentUserId, ongoingSyncConsent = true)
		jdbcTemplate.update(
			"UPDATE external_connections SET last_synced_at = ?, next_sync_after = ? WHERE id = ?",
			java.sql.Timestamp.from(Instant.parse("2005-01-02T00:00:00Z")),
			java.sql.Timestamp.from(NOW.minusSeconds(2)),
			oldConnectionId,
		)
		jdbcTemplate.update(
			"UPDATE external_connections SET next_sync_after = ? WHERE id = ?",
			java.sql.Timestamp.from(NOW.minusSeconds(1)), recentConnectionId,
		)

		assertThat(monitoringScheduler.enqueueDue()).isEqualTo(2)
		val oldRange = jdbcTemplate.queryForMap(
			"SELECT date_from, date_to FROM import_runs WHERE connection_id = ? AND status = 'QUEUED'",
			oldConnectionId,
		)
		assertThat(oldRange["date_from"].toString()).isEqualTo("2005-01-01")
		assertThat(oldRange["date_to"].toString()).isEqualTo("2015-01-01")
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM import_runs WHERE connection_id = ? AND status = 'QUEUED'",
				Long::class.java,
				recentConnectionId,
			),
		).isEqualTo(1)
		cleanupMonitorConnections(oldConnectionId, recentConnectionId)
	}

	@Test
	fun `한 연결의 잘못된 체크포인트는 뒤 연결의 모니터 큐잉을 중단하지 않는다`() {
		val brokenUserId = UUID.randomUUID()
		val healthyUserId = UUID.randomUUID()
		val brokenConnectionId = connectNaver(brokenUserId, ongoingSyncConsent = true)
		val healthyConnectionId = connectNaver(healthyUserId, ongoingSyncConsent = true)
		jdbcTemplate.update(
			"UPDATE external_connections SET last_synced_at = ?, next_sync_after = ? WHERE id = ?",
			java.sql.Timestamp.from(NOW.plusSeconds(3 * 24 * 60 * 60)),
			java.sql.Timestamp.from(NOW.minusSeconds(2)),
			brokenConnectionId,
		)
		jdbcTemplate.update(
			"UPDATE external_connections SET next_sync_after = ? WHERE id = ?",
			java.sql.Timestamp.from(NOW.minusSeconds(1)), healthyConnectionId,
		)

		assertThat(monitoringScheduler.enqueueDue()).isEqualTo(1)
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM import_runs WHERE connection_id = ?", Long::class.java, brokenConnectionId,
			),
		).isZero()
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM import_runs WHERE connection_id = ? AND status = 'QUEUED'",
				Long::class.java,
				healthyConnectionId,
			),
		).isEqualTo(1)
		cleanupMonitorConnections(brokenConnectionId, healthyConnectionId)
	}

	@Test
	fun `자동 확정된 최종 합격 메일은 검토 필요 상태로 저장된다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		testMailCollector.useLowConfidenceOffer(connectionId)
		processRun(createRun(userId, connectionId))
		val applicationId = jdbcTemplate.queryForObject(
			"SELECT id FROM applications WHERE user_id = ?", UUID::class.java, userId,
		) ?: error("자동 확정된 지원이 없습니다.")
		mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.status").value("offered"))
			.andExpect(jsonPath("$.needsReview").value(true))
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM import_drafts WHERE user_id = ?", Long::class.java, userId,
			),
		).isZero()
	}

	@Test
	fun `오래된 후속 메일은 최종 결과나 완료된 최신 일정을 되돌리지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		processRun(createRun(userId, connectionId))
		val applicationId = jdbcTemplate.queryForObject(
			"SELECT id FROM applications WHERE user_id = ?", UUID::class.java, userId,
		) ?: error("자동 확정된 지원이 없습니다.")
		processRun(createRun(userId, connectionId))
		val offered = json(
			mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
				.andExpect(status().isOk)
				.andExpect(jsonPath("$.status").value("offered"))
				.andReturn().response.contentAsString,
		)
		mockMvc.perform(
				post("/api/v1/applications/{id}/schedule/complete", applicationId)
					.header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON)
					.content(
						objectMapper.writeValueAsString(
							mapOf("mutationId" to UUID.randomUUID(), "expectedVersion" to offered.path("version").asLong()),
						),
					),
			).andExpect(status().isOk)
		mockMvc.perform(get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.completed").value(true))

		testMailCollector.useOldActive(connectionId)
		processRun(createRun(userId, connectionId))
		mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.status").value("offered"))
		val emailPage = json(
			mockMvc.perform(
				get("/api/v1/applications/{id}/emails", applicationId).header(USER_HEADER, userId),
			).andExpect(status().isOk).andReturn().response.contentAsString,
		)
		assertThat(emailPage.path("items").get(0).path("receivedAt").asString())
			.isEqualTo("2026-08-14T02:00:00Z")
		mockMvc.perform(get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.completed").value(true))
			.andExpect(jsonPath("$.scheduledAt").value("2026-08-25T01:00:00Z"))
	}

	@Test
	fun `더 늦게 받은 일정 변경 메일은 날짜가 앞당겨져도 완료 일정을 다음 전형으로 갱신한다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		processRun(createRun(userId, connectionId))
		val applicationId = jdbcTemplate.queryForObject(
			"SELECT id FROM applications WHERE user_id = ?", UUID::class.java, userId,
		) ?: error("자동 확정된 지원이 없습니다.")

		processRun(createRun(userId, connectionId))
		val offered = json(
			mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
				.andExpect(status().isOk).andReturn().response.contentAsString,
		)
		mockMvc.perform(
				post("/api/v1/applications/{id}/schedule/complete", applicationId)
					.header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(mapOf(
						"mutationId" to UUID.randomUUID(), "expectedVersion" to offered.path("version").asLong(),
					))),
			).andExpect(status().isOk)

		testMailCollector.useNewEarlierSchedule(connectionId)
		processRun(createRun(userId, connectionId))
		mockMvc.perform(get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.scheduledAt").value("2026-08-20T00:00:00Z"))
			.andExpect(jsonPath("$.completed").value(false))
	}

	@Test
	fun `사용자가 직접 편집한 일정은 더 늦게 받은 메일이 덮어쓰지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		processRun(createRun(userId, connectionId))
		val applicationId = jdbcTemplate.queryForObject(
			"SELECT id FROM applications WHERE user_id = ?", UUID::class.java, userId,
		) ?: error("자동 확정된 지원이 없습니다.")
		val created = json(
			mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
				.andExpect(status().isOk).andReturn().response.contentAsString,
		)
		val schedule = json(
			mockMvc.perform(get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId))
				.andExpect(status().isOk).andReturn().response.contentAsString,
		)
		mockMvc.perform(
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
					"/api/v1/applications/{id}/schedule", applicationId,
				).header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(mapOf(
						"mutationId" to UUID.randomUUID(),
						"expectedVersion" to created.path("version").asLong(),
						"expectedScheduleVersion" to schedule.path("version").asLong(),
						"scheduleType" to "interview", "action" to "직접 편집한 면접",
						"scheduledAt" to "2026-08-30T00:00:00Z", "endsAt" to "2026-08-30T01:00:00Z",
						"timezone" to "Asia/Seoul", "location" to "직접 입력 장소", "description" to "직접 입력",
					))),
			).andExpect(status().isOk)

		testMailCollector.useNewEarlierSchedule(connectionId)
		processRun(createRun(userId, connectionId))
		mockMvc.perform(get("/api/v1/applications/{id}/schedule", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.scheduledAt").value("2026-08-30T00:00:00Z"))
			.andExpect(jsonPath("$.action").value("직접 편집한 면접"))
			.andExpect(jsonPath("$.location").value("직접 입력 장소"))
	}

	@Test
	fun `수집 뒤 완료 직전에 자동 확인 동의를 철회하면 메시지를 확정하지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId, ongoingSyncConsent = true)
		assertThat(monitoringScheduler.enqueueDue()).isEqualTo(1)
		val claim = claimService.claimNext() ?: error("claim이 없습니다.")
		jdbcTemplate.update(
			"UPDATE external_connections SET ongoing_sync_consent = false, next_sync_after = NULL WHERE id = ?",
			connectionId,
		)
		completionService.complete(
			claim,
			MailCollectionResult(listOf(
				MailCandidate(
					ConnectionProvider.NAVER, "late-consent-message", "[Acme] 면접 안내",
					"recruit@acme.example", NOW, "2026-08-20 14:00 면접입니다.",
				),
			), connectionVersion(connectionId)),
		)
		assertThat(
			jdbcTemplate.queryForObject("SELECT status FROM import_runs WHERE id = ?", String::class.java, claim.runId),
		).isEqualTo("CANCELLED")
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM applications WHERE user_id = ?", Long::class.java, userId,
			),
		).isZero()
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT count(*) FROM mail_ingestion_ledger WHERE user_id = ?", Long::class.java, userId,
			),
		).isZero()
	}

	@Test
	fun `이미 확정한 메시지 재수집은 검토 완료 상태를 다시 열지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		testMailCollector.useLowConfidence(connectionId)
		val firstRun = createRun(userId, connectionId)
		assertThat(worker.runOnce()).isTrue()
		val applicationId = jdbcTemplate.queryForObject(
			"SELECT id FROM applications WHERE user_id = ?", UUID::class.java, userId,
		) ?: error("자동 확정된 지원이 없습니다.")
		val imported = json(
			mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
				.andExpect(status().isOk)
				.andExpect(jsonPath("$.needsReview").value(true))
				.andReturn().response.contentAsString,
		)
		mockMvc.perform(
			post("/api/v1/applications/{id}/review/complete", applicationId)
				.header(USER_HEADER, userId).contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf("mutationId" to UUID.randomUUID(), "expectedVersion" to imported.path("version").asLong()),
					),
				),
		).andExpect(status().isOk).andExpect(jsonPath("$.needsReview").value(false))

		jdbcTemplate.update("DELETE FROM import_runs WHERE id = ?", UUID.fromString(firstRun.path("id").asString()))
		testMailCollector.reset(connectionId)
		testMailCollector.useLowConfidence(connectionId)
		val replay = createRun(userId, connectionId)
		assertThat(worker.runOnce()).isTrue()
		mockMvc.perform(get("/api/v1/import-runs/{id}", replay.path("id").asString()).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.finalizedCount").value(0))
			.andExpect(jsonPath("$.ignoredCount").value(0))
			.andExpect(jsonPath("$.duplicateCount").value(1))
		mockMvc.perform(get("/api/v1/applications/{id}", applicationId).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.needsReview").value(false))
	}

	@Test
	fun `legacy FINALIZED 메시지의 exact process key는 후속 메일을 기존 지원에 연결한다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		val application = json(
			mockMvc.perform(
				post("/api/v1/applications")
					.header(USER_HEADER, userId)
					.contentType(MediaType.APPLICATION_JSON)
					.content(
						objectMapper.writeValueAsString(
							mapOf(
								"mutationId" to UUID.randomUUID(),
								"company" to "Acme",
								"position" to "백엔드 엔지니어",
								"status" to "applied",
							),
						),
			),
		).andExpect(status().isCreated).andReturn().response.contentAsString,
		)
		val applicationId = UUID.fromString(application.path("id").asString())
		jdbcTemplate.update(
			"""
				INSERT INTO mail_ingestion_ledger (
				    id, user_id, connection_id, provider_message_id, state, application_id,
				    first_seen_at, updated_at
				) VALUES (?, ?, ?, 'legacy-terminal-message', 'FINALIZED', ?, ?, ?)
			""".trimIndent(),
			UUID.randomUUID(), userId, connectionId, applicationId,
			java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
		)

		createRun(userId, connectionId)
		completionService.complete(
			claimService.claimNext() ?: error("claim이 없습니다."),
			MailCollectionResult(
				listOf(
					MailCandidate(
						ConnectionProvider.NAVER,
						"legacy-terminal-message",
						"[Acme] 지원 접수 안내",
						"recruit@acme.example",
						NOW,
						"지원이 접수되었습니다.",
						providerProcessKeys = setOf("legacy-exact-process"),
					),
				),
				connectionVersion(connectionId),
			),
		)
		assertThat(jdbcTemplate.queryForObject(
			"""
				SELECT application_id FROM provider_process_bindings
				WHERE user_id = ? AND connection_id = ? AND provider_process_key = ?
			""".trimIndent(),
			UUID::class.java,
			userId,
			connectionId,
			"legacy-exact-process",
		)).isEqualTo(applicationId)

		createRun(userId, connectionId)
		completionService.complete(
			claimService.claimNext() ?: error("claim이 없습니다."),
			MailCollectionResult(
				listOf(
					MailCandidate(
						ConnectionProvider.NAVER,
						"legacy-follow-up-message",
						"[Acme] 면접 안내",
						"recruit@acme.example",
						NOW.plusSeconds(60),
						"2026-08-20 14:00 면접입니다.",
						providerProcessKeys = setOf("legacy-exact-process"),
					),
				),
				connectionVersion(connectionId),
			),
		)
		assertThat(applicationCount(userId)).isEqualTo(1)
		assertThat(jdbcTemplate.queryForObject(
			"""
				SELECT application_id FROM mail_ingestion_ledger
				WHERE user_id = ? AND connection_id = ? AND provider_message_id = 'legacy-follow-up-message'
			""".trimIndent(),
			UUID::class.java,
			userId,
			connectionId,
		)).isEqualTo(applicationId)
	}

	@Test
	fun `같은 회사라도 provider process key가 없으면 지원을 추측 병합하지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		repeat(2) { index ->
			createRun(userId, connectionId)
			val claim = claimService.claimNext() ?: error("claim이 없습니다.")
			completionService.complete(
				claim,
				MailCollectionResult(
					listOf(
						MailCandidate(
							ConnectionProvider.NAVER, "independent-$index", "[Acme] 면접 안내",
							"recruit@acme.example", NOW.plusSeconds(index.toLong()),
							"2026-08-20 14:00 면접입니다.",
						),
					),
					connectionVersion(connectionId),
				),
			)
		}
		assertThat(applicationCount(userId)).isEqualTo(2)
	}

	@Test
	fun `실행 보존기간이 끝나도 terminal ledger는 이미 본 메일을 다시 반영하지 않는다`() {
		val userId = UUID.randomUUID()
		val connectionId = connectNaver(userId)
		val firstRun = createRun(userId, connectionId)
		assertThat(worker.runOnce()).isTrue()
		assertThat(applicationCount(userId)).isEqualTo(1)
		jdbcTemplate.update("DELETE FROM import_runs WHERE id = ?", UUID.fromString(firstRun.path("id").asString()))
		testMailCollector.reset(connectionId)

		val replay = createRun(userId, connectionId)
		assertThat(worker.runOnce()).isTrue()
		mockMvc.perform(get("/api/v1/import-runs/{id}", replay.path("id").asString()).header(USER_HEADER, userId))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.finalizedCount").value(0))
			.andExpect(jsonPath("$.ignoredCount").value(0))
			.andExpect(jsonPath("$.duplicateCount").value(2))
		mockMvc.perform(get("/api/v1/import-drafts").header(USER_HEADER, userId))
			.andExpect(status().isNotFound)
		assertThat(applicationCount(userId)).isEqualTo(1)
	}

	@Test
	fun `가져오기 목록 페이지 크기는 제한된다`() {
		val userId = UUID.randomUUID()
		mockMvc.perform(get("/api/v1/import-runs").param("size", "101").header(USER_HEADER, userId))
			.andExpect(status().isBadRequest)
		mockMvc.perform(get("/api/v1/import-drafts").header(USER_HEADER, userId))
			.andExpect(status().isNotFound)
	}

	private fun connectNaver(userId: UUID, ongoingSyncConsent: Boolean = false): UUID {
		val response = mockMvc.perform(
			post("/api/v1/connections/naver")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"accountEmail" to "${userId}@naver.com",
							"appPassword" to "test-app-password",
							"ongoingSyncConsent" to ongoingSyncConsent,
						),
					),
				),
		).andExpect(status().isCreated).andReturn().response.contentAsString
		return UUID.fromString(json(response).path("id").asString())
	}

	private fun connectGmail(userId: UUID, accountEmail: String): UUID {
		jdbcTemplate.update(
			"INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
			userId,
			java.sql.Timestamp.from(NOW),
			java.sql.Timestamp.from(NOW),
		)
		val connection = ExternalConnection.createOAuth(
			UUID.randomUUID(),
			userId,
			ConnectionProvider.GMAIL,
			accountEmail,
			"encrypted-access-token",
			"encrypted-refresh-token",
			NOW.plusSeconds(3600),
			setOf("https://www.googleapis.com/auth/gmail.readonly"),
			false,
			NOW,
		)
		return connectionRepository.saveAndFlush(connection).id
	}

	private fun createRun(userId: UUID, connectionId: UUID): JsonNode {
		val response = mockMvc.perform(
			post("/api/v1/import-runs")
				.header(USER_HEADER, userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(importRunBody(connectionId)),
		).andExpect(status().isAccepted)
			.andExpect(jsonPath("$.status").value("queued"))
			.andReturn().response.contentAsString
		return json(response)
	}

	private fun processRun(run: JsonNode) {
		val runId = UUID.fromString(run.path("id").asString())
		repeat(20) {
			worker.runOnce()
			val status = jdbcTemplate.queryForObject(
				"SELECT status FROM import_runs WHERE id = ?", String::class.java, runId,
			)
			if (status in setOf("COMPLETED", "FAILED", "CANCELLED")) return
		}
		error("가져오기 실행이 완료되지 않았습니다: $runId")
	}

	private fun awaitRunStatus(runId: UUID, expectedStatus: String) {
		repeat(100) {
			val status = jdbcTemplate.queryForObject(
				"SELECT status FROM import_runs WHERE id = ?", String::class.java, runId,
			)
			if (status == expectedStatus) return
			Thread.sleep(20)
		}
		error("가져오기 실행이 $expectedStatus 상태가 되지 않았습니다: $runId")
	}

	private fun importRunBody(connectionId: UUID): String = objectMapper.writeValueAsString(
		mapOf(
			"mutationId" to UUID.randomUUID(),
			"connectionId" to connectionId,
			"dateFrom" to "2021-01-01",
			"dateTo" to "2026-08-17",
		),
	)

	private fun applicationCount(userId: UUID): Long = jdbcTemplate.queryForObject(
		"SELECT count(*) FROM applications WHERE user_id = ?",
		Long::class.java,
		userId,
	) ?: 0

	private fun connectionVersion(connectionId: UUID): Long = jdbcTemplate.queryForObject(
		"SELECT version FROM external_connections WHERE id = ?",
		Long::class.java,
		connectionId,
	) ?: error("외부 연결 version이 없습니다.")

	private fun cleanupMonitorConnections(firstConnectionId: UUID, secondConnectionId: UUID) {
		jdbcTemplate.update(
			"""
				UPDATE import_runs
				SET status = 'CANCELLED', error_code = 'TEST_CLEANUP', completed_at = ?, updated_at = ?
				WHERE connection_id IN (?, ?) AND status = 'QUEUED'
			""".trimIndent(),
			java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), firstConnectionId, secondConnectionId,
		)
		jdbcTemplate.update(
			"""
				UPDATE external_connections
				SET ongoing_sync_consent = false, next_sync_after = NULL
				WHERE id IN (?, ?)
			""".trimIndent(),
			firstConnectionId, secondConnectionId,
		)
	}

	private fun storedRawMailColumns(): List<String> = jdbcTemplate.queryForList(
		"""
			SELECT column_name
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name IN ('import_drafts', 'application_emails')
			  AND column_name IN ('raw_body', 'raw_html', 'attachment', 'attachment_bytes')
		""".trimIndent(),
		String::class.java,
	).filterNotNull()

	private fun json(value: String): JsonNode = objectMapper.readTree(value)

	@TestConfiguration
	class ImportTestConfiguration {
		@Bean
		@Primary
		fun fixedClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

		@Bean
		@Primary
		fun naverCredentialValidator(): NaverCredentialValidator = NaverCredentialValidator { _, password ->
			check(password == "test-app-password")
		}

		@Bean
		@Primary
		fun mailCollector(): TestMailCollector = TestMailCollector()
	}

	class TestMailCollector : MailCollector {
		private val calls = ConcurrentHashMap<UUID, AtomicInteger>()
		private val blockingConnections = ConcurrentHashMap<UUID, CollectionGate>()
		private val lowConfidenceConnections = ConcurrentHashMap.newKeySet<UUID>()
		private val lowConfidenceOfferConnections = ConcurrentHashMap.newKeySet<UUID>()
		private val oldActiveConnections = ConcurrentHashMap.newKeySet<UUID>()
		private val serviceUnavailableConnections = ConcurrentHashMap.newKeySet<UUID>()
		private val newEarlierScheduleConnections = ConcurrentHashMap.newKeySet<UUID>()

		override fun collect(
			connection: com.meenseek.jobvis.connection.ExternalConnection,
			dateFrom: java.time.LocalDate,
			dateTo: java.time.LocalDate,
		): com.meenseek.jobvis.imports.MailCollectionResult {
			val call = calls.computeIfAbsent(connection.id) { AtomicInteger() }.incrementAndGet()
			blockingConnections.remove(connection.id)?.let { gate ->
				gate.started.countDown()
				check(gate.release.await(10, TimeUnit.SECONDS))
			}
			if (serviceUnavailableConnections.remove(connection.id)) {
				throw ServiceUnavailableException("temporary credential refresh outage")
			}
			if (newEarlierScheduleConnections.remove(connection.id)) {
				return com.meenseek.jobvis.imports.MailCollectionResult(listOf(
					MailCandidate(
						ConnectionProvider.NAVER, "NEW-EARLIER:$call", "2차 면접 일정 변경 안내",
						"recruit@acme.example", Instant.parse("2026-08-17T00:30:00Z"),
						"면접 일정이 2026-08-20 09:00로 변경되었습니다.",
						providerProcessKeys = setOf("test-acme-process"),
					),
				), connection.version)
			}
			if (oldActiveConnections.remove(connection.id)) {
				return com.meenseek.jobvis.imports.MailCollectionResult(listOf(
					MailCandidate(
						ConnectionProvider.NAVER, "OLD-ACTIVE:$call", "지원 접수 채용 일정", "noreply@example.com",
						Instant.parse("2026-08-14T02:00:00Z"), "2026-08-18 10:00 채용 일정을 확인해 주세요.",
						providerProcessKeys = setOf("test-acme-process"),
					),
				), connection.version)
			}
			if (lowConfidenceOfferConnections.remove(connection.id)) {
				return com.meenseek.jobvis.imports.MailCollectionResult(listOf(
					MailCandidate(
						ConnectionProvider.NAVER, "LOW-OFFER:$call", "최종 합격 채용 안내", "noreply@gmail.com",
						Instant.parse("2026-08-15T02:00:00Z"), "최종 합격 안내입니다.",
						providerProcessKeys = setOf("test-acme-process"),
					),
				), connection.version)
			}
			if (lowConfidenceConnections.remove(connection.id)) {
				return com.meenseek.jobvis.imports.MailCollectionResult(listOf(
					MailCandidate(
						ConnectionProvider.NAVER, "LOW:$call", "지원 접수 안내", "noreply@example.com",
						Instant.parse("2026-08-15T02:00:00Z"), "지원이 접수되었습니다.",
						providerProcessKeys = setOf("test-acme-process"),
					),
				), connection.version)
			}
			val isFollowUp = call > 1
			return com.meenseek.jobvis.imports.MailCollectionResult(listOf(
				MailCandidate(
					ConnectionProvider.NAVER,
					"INBOX:${call}001",
					if (isFollowUp) "[Acme] 백엔드 엔지니어 최종 합격" else "[Acme] 백엔드 엔지니어 면접 안내",
					"Acme Recruiting <recruit@acme.example>",
					Instant.parse(if (isFollowUp) "2026-08-16T02:00:00Z" else "2026-08-15T02:00:00Z"),
					if (isFollowUp) {
						"최종 합격 및 입사 제안 안내입니다. 2026-08-25 10:00 채용 일정을 확인해 주세요."
					} else {
						"2026-08-20 14:00 면접입니다. 링크와 세부 사항을 확인해 주세요."
					},
					providerProcessKeys = setOf("test-acme-process"),
				),
				MailCandidate(
					connection.provider,
					"INBOX:${call}002",
					"주간 쇼핑 알림",
					"news@example.com",
					Instant.parse("2026-08-16T02:00:00Z"),
					"이번 주 할인 소식입니다.",
				),
			), connection.version)
		}

		fun calls(connectionId: UUID): Int = calls[connectionId]?.get() ?: 0

		fun block(connectionId: UUID): CollectionGate = CollectionGate().also { gate ->
			blockingConnections[connectionId] = gate
		}

		fun reset(connectionId: UUID) {
			calls.remove(connectionId)
		}

		fun useLowConfidence(connectionId: UUID) {
			lowConfidenceConnections.add(connectionId)
		}

		fun useLowConfidenceOffer(connectionId: UUID) {
			lowConfidenceOfferConnections.add(connectionId)
		}

		fun useOldActive(connectionId: UUID) {
			oldActiveConnections.add(connectionId)
		}

		fun useServiceUnavailable(connectionId: UUID) {
			serviceUnavailableConnections.add(connectionId)
		}

		fun useNewEarlierSchedule(connectionId: UUID) {
			newEarlierScheduleConnections.add(connectionId)
		}
	}

	class CollectionGate(
		val started: CountDownLatch = CountDownLatch(1),
		val release: CountDownLatch = CountDownLatch(1),
	)

	private companion object {
		const val USER_HEADER = "X-Jobvis-User-Id"
		val NOW: Instant = Instant.parse("2026-08-17T00:00:00Z")
	}
}
