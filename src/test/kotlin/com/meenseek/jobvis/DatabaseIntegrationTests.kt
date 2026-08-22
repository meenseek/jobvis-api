package com.meenseek.jobvis

import com.meenseek.jobvis.imports.GmailQuotaGate
import com.meenseek.jobvis.imports.ImportRetentionWorker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest
@ActiveProfiles("local")
class DatabaseIntegrationTests @Autowired constructor(
	private val flyway: Flyway,
	private val jdbcTemplate: JdbcTemplate,
	private val gmailQuotaGate: GmailQuotaGate,
	private val dataSource: DataSource,
) : PostgresIntegrationTest() {
	@Test
	fun `빈 DB 마이그레이션은 완료되고 재실행은 안전하다`() {
		assertThat(flyway.info().current().version.version).isEqualTo("2")
		assertThat(flyway.migrate().migrationsExecuted).isZero()
	}

	@Test
	fun `Gmail quota permit과 account block은 호출과 인스턴스 사이에서 공유된다`() {
		val accountKey = gmailQuotaGate.accountKey("Shared-Account@Example.com")
		jdbcTemplate.update("DELETE FROM gmail_quota_gates WHERE account_key = ?", accountKey)
		try {
			val firstWait = requireNotNull(gmailQuotaGate.reservePermitWaitMillis(accountKey, 60_000))
			val secondWait = requireNotNull(gmailQuotaGate.reservePermitWaitMillis(accountKey, 60_000))
			val anotherInstance = GmailQuotaGate(jdbcTemplate)
			val thirdWait = requireNotNull(anotherInstance.reservePermitWaitMillis(accountKey, 60_000))

			assertThat(firstWait).isLessThan(100)
			assertThat(secondWait).isGreaterThanOrEqualTo(150)
			assertThat(thirdWait).isGreaterThan(secondWait)

			val databaseNow = requireNotNull(
				jdbcTemplate.queryForObject("SELECT clock_timestamp()", Timestamp::class.java),
			)
			gmailQuotaGate.block(accountKey, 1_000, databaseNow.toInstant().plusSeconds(120))
			assertThat(anotherInstance.remainingBlockMillis(accountKey)).isGreaterThanOrEqualTo(119_000)
			val nextPermitBeforeRejectedWaiter = jdbcTemplate.queryForObject(
				"SELECT next_permit_at FROM gmail_quota_gates WHERE account_key = ?",
				Timestamp::class.java,
				accountKey,
			)
			assertThat(anotherInstance.awaitPermit(accountKey, 0)).isFalse()
			assertThat(anotherInstance.remainingBlockMillis(accountKey)).isGreaterThanOrEqualTo(119_000)
			assertThat(
				jdbcTemplate.queryForObject(
					"SELECT next_permit_at FROM gmail_quota_gates WHERE account_key = ?",
					Timestamp::class.java,
					accountKey,
				),
			).isEqualTo(nextPermitBeforeRejectedWaiter)
		} finally {
			jdbcTemplate.update("DELETE FROM gmail_quota_gates WHERE account_key = ?", accountKey)
		}
	}

	@Test
	fun `Gmail quota permit은 row lock 대기 예산을 넘기지 않는다`() {
		val accountKey = gmailQuotaGate.accountKey("Locked-Account@Example.com")
		jdbcTemplate.update("DELETE FROM gmail_quota_gates WHERE account_key = ?", accountKey)
		requireNotNull(gmailQuotaGate.reservePermitWaitMillis(accountKey, 60_000))
		dataSource.connection.use { lockingConnection ->
			lockingConnection.autoCommit = false
			try {
				lockingConnection.prepareStatement(
					"SELECT account_key FROM gmail_quota_gates WHERE account_key = ? FOR UPDATE",
				).use { statement ->
					statement.setString(1, accountKey)
					statement.executeQuery().use { resultSet -> assertThat(resultSet.next()).isTrue() }
				}
				val startedAt = System.nanoTime()
				assertThat(gmailQuotaGate.awaitPermit(accountKey, 250)).isFalse()
				val elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
				assertThat(elapsedMillis).isLessThan(1_000)
			} finally {
				lockingConnection.rollback()
			}
		}
		jdbcTemplate.update("DELETE FROM gmail_quota_gates WHERE account_key = ?", accountKey)
	}

	@Test
	fun `Gmail quota cleanup은 JVM clock skew에도 활성 gate를 보존한다`() {
		val activeKey = gmailQuotaGate.accountKey("Active-Gate@Example.com")
		val expiredKey = gmailQuotaGate.accountKey("Expired-Gate@Example.com")
		jdbcTemplate.update("DELETE FROM gmail_quota_gates WHERE account_key IN (?, ?)", activeKey, expiredKey)
		try {
			jdbcTemplate.update(
				"""
					INSERT INTO gmail_quota_gates (account_key, next_permit_at, blocked_until, updated_at)
					VALUES
						(?, clock_timestamp(), clock_timestamp() + interval '1 day', clock_timestamp() - interval '8 days'),
						(?, clock_timestamp() - interval '8 days', clock_timestamp() - interval '8 days', clock_timestamp() - interval '8 days')
				""".trimIndent(),
				activeKey,
				expiredKey,
			)
			val fastClock = Clock.offset(Clock.systemUTC(), Duration.ofDays(8))
			ImportRetentionWorker(jdbcTemplate, fastClock).purgeExpiredGmailQuotaGates()

			assertThat(
				jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM gmail_quota_gates WHERE account_key = ?",
					Long::class.java,
					activeKey,
				),
			).isOne()
			assertThat(
				jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM gmail_quota_gates WHERE account_key = ?",
					Long::class.java,
					expiredKey,
				),
			).isZero()
		} finally {
			jdbcTemplate.update("DELETE FROM gmail_quota_gates WHERE account_key IN (?, ?)", activeKey, expiredKey)
		}
	}

	@Test
	fun `복합 FK는 다른 사용자의 지원에 자식을 연결하지 못하게 한다`() {
		val ownerId = UUID.randomUUID()
		val anotherUserId = UUID.randomUUID()
		val applicationId = UUID.randomUUID()
		val now = Instant.parse("2026-08-17T00:00:00Z")
		insertUser(ownerId, now)
		insertUser(anotherUserId, now)
		insertApplication(ownerId, applicationId, now)

		assertThatThrownBy {
			jdbcTemplate.update(
				"""
					INSERT INTO application_schedules (
					    id, user_id, application_id, schedule_type, action, scheduled_at,
					    completed, completed_at, created_at, updated_at
					) VALUES (?, ?, ?, 'OTHER', '세부 정보 보완', NULL, false, NULL, ?, ?)
				""".trimIndent(),
				UUID.randomUUID(),
				anotherUserId,
				applicationId,
				Timestamp.from(now),
				Timestamp.from(now),
			)
		}.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	@Test
	fun `지원별 일정은 하나만 허용하고 잘못된 상태 조합을 거부한다`() {
		val userId = UUID.randomUUID()
		val applicationId = UUID.randomUUID()
		val now = Instant.parse("2026-08-17T00:00:00Z")
		insertUser(userId, now)
		insertApplication(userId, applicationId, now)
		insertSchedule(userId, applicationId, now)

		assertThatThrownBy { insertSchedule(userId, applicationId, now) }
			.isInstanceOf(DataIntegrityViolationException::class.java)

		assertThatThrownBy {
			jdbcTemplate.update(
				"""
					UPDATE applications
					SET result = 'OFFERED', stage = 'SCREENING', highest_stage_reached = 'SCREENING'
					WHERE user_id = ? AND id = ?
				""".trimIndent(),
				userId,
				applicationId,
			)
		}.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	@Test
	fun `지원 메일은 다른 사용자의 외부 연결을 참조할 수 없다`() {
		val ownerId = UUID.randomUUID()
		val anotherUserId = UUID.randomUUID()
		val applicationId = UUID.randomUUID()
		val connectionId = UUID.randomUUID()
		val now = Instant.parse("2026-08-17T00:00:00Z")
		insertUser(ownerId, now)
		insertUser(anotherUserId, now)
		insertApplication(ownerId, applicationId, now)
		jdbcTemplate.update(
			"""
				INSERT INTO external_connections (
				    id, user_id, provider, account_email, credential_kind, encrypted_app_password,
				    granted_scopes, status, ongoing_sync_consent, consented_at, version, created_at, updated_at
				) VALUES (?, ?, 'NAVER', 'other@naver.com', 'APP_PASSWORD', 'encrypted',
				          'imap.readonly', 'CONNECTED', false, ?, 0, ?, ?)
			""".trimIndent(),
			connectionId, anotherUserId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
		)

		assertThatThrownBy {
			jdbcTemplate.update(
				"""
					INSERT INTO application_emails (
					    id, user_id, application_id, connection_id, provider, provider_message_id,
					    subject, sender, received_at, summary, created_at
					) VALUES (?, ?, ?, ?, 'naver', 'cross-tenant', '제목', 'sender@example.com', ?, '요약', ?)
				""".trimIndent(),
				UUID.randomUUID(), ownerId, applicationId, connectionId, Timestamp.from(now), Timestamp.from(now),
			)
		}.isInstanceOf(DataIntegrityViolationException::class.java)
	}

	private fun insertUser(userId: UUID, now: Instant) {
		jdbcTemplate.update(
			"INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
			userId,
			Timestamp.from(now),
			Timestamp.from(now),
		)
	}

	private fun insertApplication(userId: UUID, applicationId: UUID, now: Instant) {
		jdbcTemplate.update(
			"""
				INSERT INTO applications (
				    id, user_id, company, position, location, employment_type, applied_at,
				    stage, highest_stage_reached, screening_passed, result, needs_review,
				    source, memo, creation_mutation_id, last_mutation_id, version, created_at, updated_at
				) VALUES (?, ?, '회사', '포지션', '서울', '정규직', ?, 'APPLIED', 'APPLIED',
				          false, 'ACTIVE', false, '직접 추가', '', ?, ?, 0, ?, ?)
			""".trimIndent(),
			applicationId,
			userId,
			Date.valueOf(LocalDate.of(2026, 8, 17)),
			UUID.randomUUID(),
			UUID.randomUUID(),
			Timestamp.from(now),
			Timestamp.from(now),
		)
	}

	private fun insertSchedule(userId: UUID, applicationId: UUID, now: Instant) {
		jdbcTemplate.update(
			"""
				INSERT INTO application_schedules (
				    id, user_id, application_id, schedule_type, action, scheduled_at,
				    completed, completed_at, created_at, updated_at
				) VALUES (?, ?, ?, 'OTHER', '세부 정보 보완', NULL, false, NULL, ?, ?)
			""".trimIndent(),
			UUID.randomUUID(),
			userId,
			applicationId,
			Timestamp.from(now),
			Timestamp.from(now),
		)
	}
}
