package com.meenseek.jobvis

import com.meenseek.jobvis.imports.GmailQuotaGate
import com.meenseek.jobvis.imports.ImportRetentionWorker
import com.meenseek.jobvis.imports.MailFinalizationRolloutService
import com.meenseek.jobvis.imports.NaverLedgerReconciliationEntry
import com.meenseek.jobvis.imports.NaverLedgerReconciliationFile
import com.meenseek.jobvis.imports.NaverLedgerReconciliationResult
import com.meenseek.jobvis.imports.NaverLedgerReconciliationService
import com.meenseek.jobvis.imports.lockMailFinalizationRollout
import com.meenseek.jobvis.common.ConflictException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Date
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

@SpringBootTest
@ActiveProfiles("local")
class DatabaseIntegrationTests @Autowired constructor(
	private val flyway: Flyway,
	private val jdbcTemplate: JdbcTemplate,
	private val gmailQuotaGate: GmailQuotaGate,
	private val naverLedgerReconciliationService: NaverLedgerReconciliationService,
	private val dataSource: DataSource,
) : PostgresIntegrationTest() {
	@Test
	fun `빈 DB 마이그레이션은 완료되고 재실행은 안전하다`() {
		assertThat(flyway.info().pending()).isEmpty()
		assertThat(flyway.migrate().migrationsExecuted).isZero()
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT completed_at IS NOT NULL FROM mail_finalization_rollout_state WHERE singleton = true",
				Boolean::class.java,
			),
		).isTrue()
		assertThat(
			jdbcTemplate.queryForList(
				"""
					SELECT conname FROM pg_constraint
					WHERE connamespace = current_schema()::regnamespace
					  AND conname IN (
					      'ck_mail_ingestion_ledger_state_target',
					      'ck_import_drafts_status_target',
					      'ck_import_drafts_decision_target'
					  )
					ORDER BY conname
				""".trimIndent(),
				String::class.java,
			),
		).containsExactly(
			"ck_import_drafts_decision_target",
			"ck_import_drafts_status_target",
			"ck_mail_ingestion_ledger_state_target",
		)
	}

	@Test
	fun `V10은 애플리케이션 bootstrap 없이 빈 schema의 메일 rollout을 완료한다`() {
		val schema = "migration_empty_rollout_${UUID.randomUUID().toString().replace("-", "")}"
		val quotedSchema = "\"$schema\""
		jdbcTemplate.execute("CREATE SCHEMA $quotedSchema")
		try {
			val schemaFlyway = Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.schemas(schema)
				.defaultSchema(schema)
				.target(MigrationVersion.fromVersion("10"))
				.load()
			assertThat(schemaFlyway.migrate().migrationsExecuted).isEqualTo(10)
			assertThat(
				jdbcTemplate.queryForObject(
					"SELECT completed_at IS NOT NULL FROM $quotedSchema.mail_finalization_rollout_state WHERE singleton = true",
					Boolean::class.java,
				),
			).isTrue()
			assertThat(
				jdbcTemplate.queryForList(
					"""
						SELECT conname FROM pg_constraint
						WHERE connamespace = '$schema'::regnamespace
						  AND conname IN (
						      'ck_mail_ingestion_ledger_state_target',
						      'ck_import_drafts_status_target',
						      'ck_import_drafts_decision_target'
						  )
						ORDER BY conname
					""".trimIndent(),
					String::class.java,
				),
			).containsExactly(
				"ck_import_drafts_decision_target",
				"ck_import_drafts_status_target",
				"ck_mail_ingestion_ledger_state_target",
			)
		} finally {
			jdbcTemplate.execute("DROP SCHEMA $quotedSchema CASCADE")
		}
	}

	@Test
	fun `V1 데이터는 V2 마이그레이션 뒤에도 보존된다`() {
		val schema = "migration_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
		val quotedSchema = "\"$schema\""
		jdbcTemplate.execute("CREATE SCHEMA $quotedSchema")
		try {
			val v1Flyway = Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.schemas(schema)
				.defaultSchema(schema)
				.target(MigrationVersion.fromVersion("1"))
				.load()
			assertThat(v1Flyway.migrate().migrationsExecuted).isOne()

			val userId = UUID.randomUUID()
			jdbcTemplate.update(
				"INSERT INTO $quotedSchema.users (id, created_at, updated_at) VALUES (?, clock_timestamp(), clock_timestamp())",
				userId,
			)

			val currentFlyway = Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.schemas(schema)
				.defaultSchema(schema)
				.target(MigrationVersion.fromVersion("2"))
				.load()
			assertThat(currentFlyway.migrate().migrationsExecuted).isOne()
			assertThat(currentFlyway.info().pending()).isEmpty()
			assertThat(
				jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM $quotedSchema.users WHERE id = ?",
					Long::class.java,
					userId,
				),
			).isOne()
			assertThat(
				jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM $quotedSchema.gmail_quota_gates",
					Long::class.java,
				),
			).isZero()
		} finally {
			jdbcTemplate.execute("DROP SCHEMA $quotedSchema CASCADE")
		}
	}

	@Test
	fun `V3 preflight는 모든 legacy 충돌을 변경 전에 막고 정리 뒤 TEST 단계를 승격한다`() {
		val schema = "migration_test_stage_${UUID.randomUUID().toString().replace("-", "")}"
		val quotedSchema = "\"$schema\""
		jdbcTemplate.execute("CREATE SCHEMA $quotedSchema")
		try {
			val v2Flyway = Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.schemas(schema)
				.defaultSchema(schema)
				.target(MigrationVersion.fromVersion("2"))
				.load()
			assertThat(v2Flyway.migrate().migrationsExecuted).isEqualTo(2)

			val now = Instant.parse("2026-08-17T00:00:00Z")
			val userId = UUID.randomUUID()
			val promotedApplicationId = UUID.randomUUID()
			val preservedApplicationId = UUID.randomUUID()
			jdbcTemplate.update(
				"INSERT INTO $quotedSchema.users (id, created_at, updated_at) VALUES (?, ?, ?)",
				userId,
				Timestamp.from(now),
				Timestamp.from(now),
			)
			fun insertV2Application(id: UUID, stage: String, highest: String, passed: Boolean, result: String) {
				jdbcTemplate.update(
					"""
						INSERT INTO $quotedSchema.applications (
						    id, user_id, company, position, location, employment_type, applied_at,
						    stage, highest_stage_reached, screening_passed, result, needs_review,
						    source, memo, creation_mutation_id, last_mutation_id, version, created_at, updated_at
						) VALUES (?, ?, '회사', '포지션', '', '', ?, ?, ?, ?, ?, false,
						          '메일', '', ?, ?, 0, ?, ?)
					""".trimIndent(),
					id, userId, Date.valueOf("2026-08-17"), stage, highest, passed, result,
					UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(now), Timestamp.from(now),
				)
				jdbcTemplate.update(
					"""
						INSERT INTO $quotedSchema.application_schedules (
						    id, user_id, application_id, schedule_type, action, scheduled_at,
						    completed, completed_at, created_at, updated_at
						) VALUES (?, ?, ?, 'TEST', '코딩 테스트', ?, false, NULL, ?, ?)
					""".trimIndent(),
					UUID.randomUUID(), userId, id, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
				)
			}
			insertV2Application(promotedApplicationId, "SCREENING", "SCREENING", false, "ACTIVE")
			insertV2Application(preservedApplicationId, "INTERVIEW", "INTERVIEW", true, "ACTIVE")

			val connectionId = UUID.randomUUID()
			val runId = UUID.randomUUID()
			val draftId = UUID.randomUUID()
			val rejectedDraftId = UUID.randomUUID()
			jdbcTemplate.update(
				"""
					INSERT INTO $quotedSchema.external_connections (
					    id, user_id, provider, account_email, credential_kind, encrypted_access_token,
					    granted_scopes, status, ongoing_sync_consent, consented_at, version, created_at, updated_at
					) VALUES (?, ?, 'GMAIL', 'test@example.com', 'OAUTH2', 'token',
					          'mail.read', 'CONNECTED', false, ?, 0, ?, ?)
				""".trimIndent(),
				connectionId, userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
			)
			jdbcTemplate.update(
				"""
					INSERT INTO $quotedSchema.import_runs (
					    id, user_id, connection_id, connection_version, provider, requested_by,
					    date_from, date_to, status, purge_after, created_at, updated_at
					) VALUES (?, ?, ?, 0, 'GMAIL', 'USER', ?, ?, 'QUEUED', ?, ?, ?)
				""".trimIndent(),
				runId, userId, connectionId, Date.valueOf("2026-08-01"), Date.valueOf("2026-08-17"),
				Timestamp.from(now.plusSeconds(86_400)), Timestamp.from(now), Timestamp.from(now),
			)
			jdbcTemplate.update(
				"""
					INSERT INTO $quotedSchema.import_drafts (
					    id, user_id, run_id, connection_id, provider, provider_message_id, subject, sender,
					    received_at, source_summary, company, position, location, employment_type, applied_at,
					    stage, highest_stage_reached, screening_passed, result, schedule_type, schedule_action,
					    scheduled_at, confidence, status, purge_after, created_at, updated_at
					) VALUES (?, ?, ?, ?, 'GMAIL', 'message-1', '코딩 테스트', 'sender@example.com',
					          ?, '요약', '회사', '포지션', '', '', ?, 'APPLIED', 'SCREENING', false, 'ACTIVE',
					          'TEST', '코딩 테스트', ?, 0.900, 'PENDING', ?, ?, ?)
				""".trimIndent(),
				draftId, userId, runId, connectionId, Timestamp.from(now), Date.valueOf("2026-08-17"),
				Timestamp.from(now), Timestamp.from(now.plusSeconds(86_400)), Timestamp.from(now), Timestamp.from(now),
			)
			jdbcTemplate.update(
				"""
					INSERT INTO $quotedSchema.import_drafts (
					    id, user_id, run_id, connection_id, provider, provider_message_id, subject, sender,
					    received_at, source_summary, company, position, location, employment_type, applied_at,
					    stage, highest_stage_reached, screening_passed, result, schedule_type, schedule_action,
					    scheduled_at, confidence, status, decision_mutation_id, decision_fingerprint, decided_at,
					    purge_after, created_at, updated_at
					) VALUES (?, ?, ?, ?, 'GMAIL', 'message-2', '코딩 테스트', 'sender@example.com',
					          ?, '요약', '회사', '포지션', '', '', ?, 'SCREENING', 'SCREENING', false, 'ACTIVE',
					          'TEST', '코딩 테스트', ?, 0.900, 'REJECTED', ?, ?, ?, ?, ?, ?)
				""".trimIndent(),
				rejectedDraftId, userId, runId, connectionId, Timestamp.from(now), Date.valueOf("2026-08-17"),
				Timestamp.from(now), UUID.randomUUID(), "a".repeat(64), Timestamp.from(now),
				Timestamp.from(now.plusSeconds(86_400)), Timestamp.from(now), Timestamp.from(now),
			)

			val v3Flyway = Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.schemas(schema)
				.defaultSchema(schema)
				.target(MigrationVersion.fromVersion("3"))
				.load()
			assertThatThrownBy { v3Flyway.migrate() }
				.rootCause()
				.hasMessageContaining(
					"V3 preflight requires every legacy PENDING import draft to be accepted or rejected",
				)
			assertThat(
				jdbcTemplate.queryForMap(
					"SELECT stage, highest_stage_reached FROM $quotedSchema.applications WHERE id = ?",
					promotedApplicationId,
				),
			).containsEntry("stage", "SCREENING").containsEntry("highest_stage_reached", "SCREENING")

			jdbcTemplate.update("DELETE FROM $quotedSchema.import_drafts WHERE id = ?", draftId)
			val conflictingConnectionId = UUID.randomUUID()
			jdbcTemplate.update(
				"""
					INSERT INTO $quotedSchema.external_connections (
					    id, user_id, provider, account_email, credential_kind, encrypted_access_token,
					    granted_scopes, status, ongoing_sync_consent, consented_at, version, created_at, updated_at
					) VALUES (?, ?, 'GMAIL', 'other@example.com', 'OAUTH2', 'token',
					          'mail.read', 'CONNECTED', false, ?, 0, ?, ?)
				""".trimIndent(),
				conflictingConnectionId, userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
			)
			assertThatThrownBy { v3Flyway.migrate() }
				.rootCause()
				.hasMessageContaining(
					"V3 preflight requires explicit reconciliation of users with multiple active MAIL connections",
				)
			jdbcTemplate.update(
				"DELETE FROM $quotedSchema.external_connections WHERE id = ?",
				conflictingConnectionId,
			)

			jdbcTemplate.update(
				"UPDATE $quotedSchema.application_schedules SET scheduled_at = NULL WHERE application_id = ?",
				promotedApplicationId,
			)
			assertThatThrownBy { v3Flyway.migrate() }
				.rootCause()
				.hasMessageContaining(
					"V3 preflight requires every legacy application schedule to have scheduled_at",
				)
			jdbcTemplate.update(
				"UPDATE $quotedSchema.application_schedules SET scheduled_at = ? WHERE application_id = ?",
				Timestamp.from(now),
				promotedApplicationId,
			)

			assertThat(v3Flyway.migrate().migrationsExecuted).isOne()
			assertThat(
				jdbcTemplate.queryForMap(
					"SELECT stage, highest_stage_reached FROM $quotedSchema.applications WHERE id = ?",
					promotedApplicationId,
				),
			).containsEntry("stage", "TEST").containsEntry("highest_stage_reached", "TEST")
			assertThat(
				jdbcTemplate.queryForMap(
					"SELECT stage, highest_stage_reached FROM $quotedSchema.applications WHERE id = ?",
					preservedApplicationId,
				),
			).containsEntry("stage", "INTERVIEW").containsEntry("highest_stage_reached", "INTERVIEW")
			assertThat(
				jdbcTemplate.queryForMap(
					"SELECT stage, highest_stage_reached, status FROM $quotedSchema.import_drafts WHERE id = ?",
					rejectedDraftId,
				),
			).containsEntry("stage", "SCREENING")
				.containsEntry("highest_stage_reached", "SCREENING")
				.containsEntry("status", "REJECTED")

			assertThatThrownBy {
				jdbcTemplate.update(
					"""
						UPDATE $quotedSchema.applications
						SET stage = 'TEST', highest_stage_reached = 'SCREENING'
						WHERE id = ?
					""".trimIndent(),
					promotedApplicationId,
				)
			}.isInstanceOf(DataIntegrityViolationException::class.java)
		} finally {
			jdbcTemplate.execute("DROP SCHEMA $quotedSchema CASCADE")
		}
	}

	@Test
	fun `V9 rollout은 pending을 기다리고 orphan을 제거한 뒤 target constraint를 한 번만 설치한다`() {
		val schema = "migration_mail_rollout_${UUID.randomUUID().toString().replace("-", "")}"
		val quotedSchema = "\"$schema\""
		jdbcTemplate.execute("CREATE SCHEMA $quotedSchema")
		try {
			val schemaFlyway = Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.schemas(schema)
				.defaultSchema(schema)
				.target(MigrationVersion.fromVersion("9"))
				.load()
			assertThat(schemaFlyway.migrate().migrationsExecuted).isEqualTo(9)

			dataSource.connection.use { connection ->
				connection.createStatement().use { it.execute("SET search_path TO $quotedSchema") }
				try {
					val scopedDataSource = SingleConnectionDataSource(connection, true)
					val scoped = JdbcTemplate(scopedDataSource)
					val rollout = MailFinalizationRolloutService(scoped, Clock.fixed(NOW, java.time.ZoneOffset.UTC))
					val transaction = TransactionTemplate(DataSourceTransactionManager(scopedDataSource))
					val userId = UUID.randomUUID()
					val connectionId = UUID.randomUUID()
					val runId = UUID.randomUUID()
					val draftId = UUID.randomUUID()
					val ledgerId = UUID.randomUUID()

					scoped.update(
						"INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
						userId, Timestamp.from(NOW), Timestamp.from(NOW),
					)
					scoped.update(
						"""
							INSERT INTO external_connections (
							    id, user_id, provider, account_email, credential_kind, encrypted_access_token,
							    granted_scopes, status, ongoing_sync_consent, consented_at, version, created_at, updated_at
							) VALUES (?, ?, 'GMAIL', 'rollout@example.com', 'OAUTH2', 'encrypted',
							          'mail.read', 'CONNECTED', false, ?, 0, ?, ?)
						""".trimIndent(),
						connectionId, userId, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW),
					)
					scoped.update(
						"""
							INSERT INTO import_runs (
							    id, user_id, connection_id, connection_version, provider, requested_by,
							    mutation_id, request_fingerprint,
							    date_from, date_to, status, completed_at, purge_after, created_at, updated_at
							) VALUES (?, ?, ?, 0, 'GMAIL', 'USER', ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?)
						""".trimIndent(),
						runId, userId, connectionId, UUID.randomUUID(), "test-fixture",
						Date.valueOf("2026-08-01"), Date.valueOf("2026-08-17"),
						Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(86_400)),
						Timestamp.from(NOW), Timestamp.from(NOW),
					)
					scoped.update(
						"""
							INSERT INTO import_drafts (
							    id, user_id, run_id, connection_id, provider, provider_message_id,
							    subject, sender, received_at, source_summary, company, position,
							    location, employment_type, applied_at, stage, highest_stage_reached,
							    screening_passed, result, confidence, status, purge_after, created_at, updated_at
							) VALUES (?, ?, ?, ?, 'GMAIL', 'legacy-message', '지원 접수', 'sender@example.com',
							          ?, '요약', '회사', '포지션', '', '', ?, 'APPLIED', 'APPLIED',
							          false, 'ACTIVE', 0.900, 'PENDING', ?, ?, ?)
						""".trimIndent(),
						draftId, userId, runId, connectionId, Timestamp.from(NOW), Date.valueOf("2026-08-17"),
						Timestamp.from(NOW.plusSeconds(86_400)), Timestamp.from(NOW), Timestamp.from(NOW),
					)
					scoped.update(
						"""
							INSERT INTO mail_ingestion_ledger (
							    id, user_id, connection_id, provider_message_id, state, first_seen_at, updated_at
							) VALUES (?, ?, ?, 'legacy-message', 'DRAFTED', ?, ?)
						""".trimIndent(),
						ledgerId, userId, connectionId, Timestamp.from(NOW), Timestamp.from(NOW),
					)

					val v10Flyway = Flyway.configure()
						.dataSource(dataSource)
						.locations("classpath:db/migration")
						.schemas(schema)
						.defaultSchema(schema)
						.target(MigrationVersion.fromVersion("10"))
						.load()
					assertThat(v10Flyway.migrate().migrationsExecuted).isOne()
					assertThat(scoped.queryForObject(
						"SELECT completed_at IS NULL FROM mail_finalization_rollout_state WHERE singleton = true",
						Boolean::class.java,
					)).isTrue()

					assertThat(transaction.execute { rollout.reconcileAndCompleteIfReady() }).isFalse()
					assertThat(scoped.queryForObject(
						"SELECT completed_at IS NULL FROM mail_finalization_rollout_state WHERE singleton = true",
						Boolean::class.java,
					)).isTrue()
					scoped.update("DELETE FROM import_drafts WHERE id = ?", draftId)

					val acceptedApplicationId = UUID.randomUUID()
					val acceptedLedgerId = UUID.randomUUID()
					scoped.update(
						"""
							INSERT INTO applications (
							    id, user_id, company, position, location, employment_type, applied_at,
							    stage, highest_stage_reached, screening_passed, result, needs_review,
							    source, source_type, memo, creation_mutation_id, last_mutation_id,
							    version, created_at, updated_at
							) VALUES (?, ?, '회사', '포지션', '', '', ?, 'APPLIED', 'APPLIED', false,
							          'ACTIVE', false, 'Gmail 메일', 'GMAIL', '', ?, ?, 0, ?, ?)
						""".trimIndent(),
						acceptedApplicationId, userId, Date.valueOf("2026-08-17"), UUID.randomUUID(),
						UUID.randomUUID(), Timestamp.from(NOW), Timestamp.from(NOW),
					)
					scoped.update(
						"""
							INSERT INTO application_emails (
							    id, user_id, application_id, connection_id, provider, provider_message_id,
							    subject, sender, received_at, summary, created_at
							) VALUES (?, ?, ?, ?, 'GMAIL', 'accepted-without-draft', '지원 접수',
							          'sender@example.com', ?, '요약', ?)
						""".trimIndent(),
						UUID.randomUUID(), userId, acceptedApplicationId, connectionId,
						Timestamp.from(NOW), Timestamp.from(NOW),
					)
					scoped.update(
						"""
							INSERT INTO mail_ingestion_ledger (
							    id, user_id, connection_id, provider_message_id, state, first_seen_at, updated_at
							) VALUES (?, ?, ?, 'accepted-without-draft', 'ACCEPTED', ?, ?)
						""".trimIndent(),
						acceptedLedgerId, userId, connectionId, Timestamp.from(NOW), Timestamp.from(NOW),
					)

					assertThat(transaction.execute { rollout.reconcileAndCompleteIfReady() }).isTrue()
					assertThat(transaction.execute { rollout.reconcileAndCompleteIfReady() }).isTrue()
					assertThat(scoped.queryForObject(
						"SELECT orphan_drafted_deleted_count FROM mail_finalization_rollout_state WHERE singleton = true",
						Long::class.java,
					)).isEqualTo(1)
					assertThat(scoped.queryForMap(
						"SELECT state, application_id FROM mail_ingestion_ledger WHERE id = ?",
						acceptedLedgerId,
					)).containsEntry("state", "FINALIZED")
						.containsEntry("application_id", acceptedApplicationId)
					assertThat(scoped.queryForList(
						"""
							SELECT conname FROM pg_constraint
							WHERE connamespace = current_schema()::regnamespace
							  AND conname IN (
							      'ck_mail_ingestion_ledger_state_target',
							      'ck_import_drafts_status_target',
							      'ck_import_drafts_decision_target'
							  )
							ORDER BY conname
						""".trimIndent(),
						String::class.java,
					)).containsExactly(
						"ck_import_drafts_decision_target",
						"ck_import_drafts_status_target",
						"ck_mail_ingestion_ledger_state_target",
					)
					assertThatThrownBy {
						scoped.update(
							"""
								INSERT INTO mail_ingestion_ledger (
								    id, user_id, connection_id, provider_message_id, state, first_seen_at, updated_at
								) VALUES (?, ?, ?, 'late-draft', 'DRAFTED', ?, ?)
							""".trimIndent(),
							UUID.randomUUID(), userId, connectionId, Timestamp.from(NOW), Timestamp.from(NOW),
						)
					}.isInstanceOf(DataIntegrityViolationException::class.java)
					assertThatThrownBy {
						scoped.update(
							"""
								INSERT INTO mail_ingestion_ledger (
								    id, user_id, connection_id, provider_message_id, state, first_seen_at, updated_at
								) VALUES (?, ?, ?, 'unowned-finalized', 'FINALIZED', ?, ?)
							""".trimIndent(),
							UUID.randomUUID(), userId, connectionId, Timestamp.from(NOW), Timestamp.from(NOW),
						)
					}.isInstanceOf(DataIntegrityViolationException::class.java)
				} finally {
					connection.createStatement().use { it.execute("RESET search_path") }
				}
			}
		} finally {
			jdbcTemplate.execute("DROP SCHEMA $quotedSchema CASCADE")
		}
	}

	@Test
	fun `Naver reconciliation은 rollout과 직렬화하고 정확한 증빙을 멱등 적용한다`() {
		val userId = UUID.randomUUID()
		val connectionId = UUID.randomUUID()
		val ledgerId = UUID.randomUUID()
		val operationId = UUID.randomUUID()
		insertUser(userId, NOW)
		val releaseRolloutLock = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			jdbcTemplate.update(
				"""
					INSERT INTO external_connections (
					    id, user_id, provider, account_email, credential_kind, encrypted_app_password,
					    granted_scopes, status, ongoing_sync_consent, consented_at, last_error_code,
					    version, created_at, updated_at
					) VALUES (?, ?, 'NAVER', 'migration@naver.com', 'APP_PASSWORD', 'encrypted',
					          'imap.readonly', 'ERROR', false, ?, 'NAVER_LEDGER_MIGRATION_REQUIRED', 0, ?, ?)
				""".trimIndent(),
				connectionId, userId, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW),
			)
			jdbcTemplate.update(
				"""
					INSERT INTO mail_ingestion_ledger (
					    id, user_id, connection_id, provider_message_id, state, first_seen_at, updated_at
				) VALUES (?, ?, ?, 'legacy-uid', 'IGNORED', ?, ?)
				""".trimIndent(),
				ledgerId, userId, connectionId, Timestamp.from(NOW), Timestamp.from(NOW),
			)
			val request = NaverLedgerReconciliationFile(
				operationId = operationId,
				connectionId = connectionId,
				expectedLedgerCount = 1,
				expectedStateCounts = mapOf("IGNORED" to 1),
				reconciledBy = "operator@example.com",
				entries = listOf(
					NaverLedgerReconciliationEntry(
						ledgerId = ledgerId,
						disposition = "STABLE_KEY",
						stableProviderMessageKey = "a".repeat(64),
						evidenceType = "PROVIDER_REFETCH",
						evidenceReference = "secure-ops-ticket-123",
					),
				),
			)

			val rolloutLockAcquired = CountDownLatch(1)
			val transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))
			val lockFuture = executor.submit {
				transaction.executeWithoutResult {
					jdbcTemplate.lockMailFinalizationRollout()
					rolloutLockAcquired.countDown()
					check(releaseRolloutLock.await(10, TimeUnit.SECONDS))
				}
			}
			assertThat(rolloutLockAcquired.await(10, TimeUnit.SECONDS)).isTrue()
			val reconciliationStarted = CountDownLatch(1)
			val reconciliationFuture = executor.submit<NaverLedgerReconciliationResult> {
				reconciliationStarted.countDown()
				naverLedgerReconciliationService.reconcile(request)
			}
			assertThat(reconciliationStarted.await(10, TimeUnit.SECONDS)).isTrue()
			assertThatThrownBy { reconciliationFuture.get(250, TimeUnit.MILLISECONDS) }
				.isInstanceOf(TimeoutException::class.java)
			releaseRolloutLock.countDown()
			assertThat(reconciliationFuture.get(10, TimeUnit.SECONDS).stableKeyCount).isEqualTo(1)
			lockFuture.get(10, TimeUnit.SECONDS)

			assertThat(naverLedgerReconciliationService.reconcile(request).stableKeyCount).isEqualTo(1)
			assertThat(jdbcTemplate.queryForMap(
				"SELECT status, last_error_code FROM external_connections WHERE id = ?", connectionId,
			)).containsEntry("status", "CONNECTED").containsEntry("last_error_code", null)
			assertThatThrownBy {
				naverLedgerReconciliationService.reconcile(
					request.copy(reconciledBy = "another-operator@example.com"),
				)
			}.isInstanceOf(ConflictException::class.java)
		} finally {
			releaseRolloutLock.countDown()
			executor.shutdownNow()
			check(executor.awaitTermination(10, TimeUnit.SECONDS))
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId)
		}
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
					    id, user_id, application_id, schedule_type, action, scheduled_at, timezone,
					    completed, completed_at, created_at, updated_at
					) VALUES (?, ?, ?, 'OTHER', '세부 정보 보완', ?, 'Asia/Seoul', false, NULL, ?, ?)
				""".trimIndent(),
				UUID.randomUUID(),
				anotherUserId,
				applicationId,
				Timestamp.from(now),
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
				    source, source_type, memo, creation_mutation_id, last_mutation_id,
				    version, created_at, updated_at
				) VALUES (?, ?, '회사', '포지션', '서울', '정규직', ?, 'APPLIED', 'APPLIED',
				          false, 'ACTIVE', false, '직접 추가', 'MANUAL', '', ?, ?, 0, ?, ?)
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
				    id, user_id, application_id, schedule_type, action, scheduled_at, timezone,
				    completed, completed_at, created_at, updated_at
				) VALUES (?, ?, ?, 'OTHER', '세부 정보 보완', ?, 'Asia/Seoul', false, NULL, ?, ?)
			""".trimIndent(),
			UUID.randomUUID(),
			userId,
			applicationId,
			Timestamp.from(now),
			Timestamp.from(now),
			Timestamp.from(now),
		)
	}

	private companion object {
		val NOW: Instant = Instant.parse("2026-08-17T00:00:00Z")
	}
}
