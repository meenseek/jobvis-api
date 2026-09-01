package com.meenseek.jobvis.imports

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

@Service
class MailFinalizationRolloutService(
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
) {
	fun isComplete(): Boolean = jdbcTemplate.queryForObject(
		"SELECT completed_at IS NOT NULL FROM mail_finalization_rollout_state WHERE singleton = true",
		Boolean::class.java,
	) == true

	@Transactional
	fun reconcileAndCompleteIfReady(): Boolean {
		jdbcTemplate.lockMailFinalizationRollout()
		val completed = jdbcTemplate.queryForObject(
			"SELECT completed_at IS NOT NULL FROM mail_finalization_rollout_state WHERE singleton = true FOR UPDATE",
			Boolean::class.java,
		) == true
		if (completed) return true

		val orphanDeleted = jdbcTemplate.update(
			"""
				DELETE FROM mail_ingestion_ledger ledger
				WHERE ledger.state = 'DRAFTED'
				  AND NOT EXISTS (
				      SELECT 1 FROM import_drafts draft
				      WHERE draft.user_id = ledger.user_id
				        AND draft.connection_id = ledger.connection_id
				        AND draft.provider_message_id = ledger.provider_message_id
				        AND draft.status = 'PENDING'
				  )
			""".trimIndent(),
		)
		val pending = count("SELECT count(*) FROM import_drafts WHERE status = 'PENDING'")
		val drafted = count("SELECT count(*) FROM mail_ingestion_ledger WHERE state = 'DRAFTED'")
		val now = Instant.now(clock)
		jdbcTemplate.update(
			"""
				UPDATE mail_finalization_rollout_state
				SET pending_draft_count = ?, drafted_ledger_count = ?,
				    orphan_drafted_deleted_count = orphan_drafted_deleted_count + ?, updated_at = ?
				WHERE singleton = true AND completed_at IS NULL
			""".trimIndent(),
			pending, drafted, orphanDeleted, Timestamp.from(now),
		)
		if (pending != 0L || drafted != 0L) return false

		val unresolvedAccepted = count(
			"""
				SELECT count(*)
				FROM mail_ingestion_ledger ledger
				LEFT JOIN application_emails email
				  ON email.user_id = ledger.user_id
				 AND email.connection_id = ledger.connection_id
				 AND email.provider_message_id = ledger.provider_message_id
				LEFT JOIN import_drafts draft
				  ON draft.user_id = ledger.user_id
				 AND draft.connection_id = ledger.connection_id
				 AND draft.provider_message_id = ledger.provider_message_id
				 AND draft.status = 'ACCEPTED'
				WHERE ledger.state = 'ACCEPTED'
				  AND (
				      COALESCE(ledger.application_id, email.application_id, draft.accepted_application_id) IS NULL
				      OR (ledger.application_id IS NOT NULL AND email.application_id IS NOT NULL
				          AND ledger.application_id <> email.application_id)
				      OR (ledger.application_id IS NOT NULL AND draft.accepted_application_id IS NOT NULL
				          AND ledger.application_id <> draft.accepted_application_id)
				      OR (email.application_id IS NOT NULL AND draft.accepted_application_id IS NOT NULL
				          AND email.application_id <> draft.accepted_application_id)
				  )
			""".trimIndent(),
		)
		check(unresolvedAccepted == 0L) {
			"Legacy ACCEPTED mail ledger application ownership must be reconciled before rollout"
		}

		jdbcTemplate.update(
			"""
				UPDATE mail_ingestion_ledger ledger
				SET application_id = email.application_id, updated_at = ?
				FROM application_emails email
				WHERE ledger.user_id = email.user_id
				  AND ledger.connection_id = email.connection_id
				  AND ledger.provider_message_id = email.provider_message_id
				  AND ledger.state = 'ACCEPTED'
			""".trimIndent(),
			Timestamp.from(now),
		)
		jdbcTemplate.update(
			"""
				UPDATE mail_ingestion_ledger ledger
				SET application_id = draft.accepted_application_id, updated_at = ?
				FROM import_drafts draft
				WHERE ledger.user_id = draft.user_id
				  AND ledger.connection_id = draft.connection_id
				  AND ledger.provider_message_id = draft.provider_message_id
				  AND ledger.state = 'ACCEPTED' AND draft.status = 'ACCEPTED'
				  AND ledger.application_id IS NULL
				  AND draft.accepted_application_id IS NOT NULL
			""".trimIndent(),
			Timestamp.from(now),
		)
		jdbcTemplate.update("UPDATE mail_ingestion_ledger SET state = 'FINALIZED' WHERE state = 'ACCEPTED'")
		jdbcTemplate.update("UPDATE mail_ingestion_ledger SET state = 'IGNORED' WHERE state = 'REJECTED'")
		jdbcTemplate.execute("ALTER TABLE mail_ingestion_ledger DROP CONSTRAINT ck_mail_ingestion_ledger_state_expand")
		jdbcTemplate.execute(
			"ALTER TABLE mail_ingestion_ledger ADD CONSTRAINT ck_mail_ingestion_ledger_state_target " +
				"CHECK ((state = 'FINALIZED' AND application_id IS NOT NULL) " +
				"OR (state = 'IGNORED' AND application_id IS NULL))",
		)
		jdbcTemplate.execute("ALTER TABLE import_drafts DROP CONSTRAINT ck_import_drafts_status_expand")
		jdbcTemplate.execute("ALTER TABLE import_drafts DROP CONSTRAINT ck_import_drafts_decision_expand")
		jdbcTemplate.execute(
			"ALTER TABLE import_drafts ADD CONSTRAINT ck_import_drafts_status_target " +
				"CHECK (status IN ('ACCEPTED', 'REJECTED', 'FAILED'))",
		)
		jdbcTemplate.execute(
			"""
				ALTER TABLE import_drafts ADD CONSTRAINT ck_import_drafts_decision_target CHECK (
				    (status = 'REJECTED' AND decided_at IS NOT NULL AND accepted_application_id IS NULL
				        AND decision_mutation_id IS NOT NULL AND decision_fingerprint IS NOT NULL
				        AND error_code IS NULL)
				    OR
				    (status = 'ACCEPTED' AND decided_at IS NOT NULL AND accepted_application_id IS NOT NULL
				        AND error_code IS NULL)
				    OR
				    (status = 'FAILED' AND decided_at IS NOT NULL AND accepted_application_id IS NULL
				        AND error_code IS NOT NULL)
				)
			""".trimIndent(),
		)
		jdbcTemplate.update(
			"""
				UPDATE external_connections connection
				SET status = CASE WHEN connection.status = 'REVOKED' THEN 'REVOKED' ELSE 'ERROR' END,
				    ongoing_sync_consent = false, next_sync_after = NULL,
				    last_error_code = 'NAVER_LEDGER_MIGRATION_REQUIRED',
				    updated_at = ?, version = version + 1
				WHERE connection.provider = 'NAVER'
				  AND EXISTS (
				      SELECT 1
				      FROM mail_ingestion_ledger ledger
				      LEFT JOIN naver_ledger_reconciliation_audits audit ON audit.ledger_id = ledger.id
				      WHERE ledger.user_id = connection.user_id
				        AND ledger.connection_id = connection.id
				        AND ledger.stable_provider_message_key IS NULL
				        AND (audit.disposition IS NULL OR audit.disposition <> 'VERIFIED_UID_ONLY')
				  )
			""".trimIndent(),
			Timestamp.from(now),
		)
		return jdbcTemplate.update(
			"""
				UPDATE mail_finalization_rollout_state
				SET completed_at = ?, updated_at = ?, pending_draft_count = 0, drafted_ledger_count = 0
				WHERE singleton = true AND completed_at IS NULL
			""".trimIndent(),
			Timestamp.from(now), Timestamp.from(now),
		) == 1
	}

	private fun count(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java) ?: 0
}
