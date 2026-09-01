package com.meenseek.jobvis.imports

import com.meenseek.jobvis.application.ActivityType
import com.meenseek.jobvis.application.ApplicationActivity
import com.meenseek.jobvis.application.ApplicationActivityRepository
import com.meenseek.jobvis.application.ApplicationChange
import com.meenseek.jobvis.application.ApplicationChangeRepository
import com.meenseek.jobvis.application.ApplicationEmail
import com.meenseek.jobvis.application.ApplicationEmailRepository
import com.meenseek.jobvis.application.ApplicationSchedule
import com.meenseek.jobvis.application.ApplicationScheduleRepository
import com.meenseek.jobvis.application.ApplicationReviewStateService
import com.meenseek.jobvis.application.ApplicationSourceType
import com.meenseek.jobvis.application.JobApplication
import com.meenseek.jobvis.application.JobApplicationRepository
import com.meenseek.jobvis.connection.ConnectionStatus
import com.meenseek.jobvis.connection.ExternalConnectionRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

enum class MailFinalizationOutcome { FINALIZED, IGNORED, DUPLICATE }

class MailFinalizationException(val errorCode: String) : RuntimeException(errorCode)
class MailFinalizationCancelledException(val errorCode: String) : RuntimeException(errorCode)

@Service
class MailFinalizationService(
	private val analyzer: RecruitmentMailAnalyzer,
	private val runRepository: ImportRunRepository,
	private val connectionRepository: ExternalConnectionRepository,
	private val applicationRepository: JobApplicationRepository,
	private val scheduleRepository: ApplicationScheduleRepository,
	private val emailRepository: ApplicationEmailRepository,
	private val activityRepository: ApplicationActivityRepository,
	private val changeRepository: ApplicationChangeRepository,
	private val reviewStateService: ApplicationReviewStateService,
	private val jdbcTemplate: JdbcTemplate,
) {
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun finalize(
		claim: ClaimedImportRun,
		snapshot: ImportRun,
		candidate: MailCandidate,
		collectionConnectionVersion: Long,
		now: Instant,
	): MailFinalizationOutcome {
		val connection = connectionRepository.findOwnedLocked(snapshot.connectionId, snapshot.userId)
			?: throw MailFinalizationCancelledException("CONNECTION_NOT_FOUND")
		val run = runRepository.findClaimedLocked(claim.runId, claim.leaseOwner)
			?: throw MailFinalizationCancelledException("IMPORT_RUN_NOT_ACTIVE")
		if (run.status != ImportRunStatus.RUNNING) {
			throw MailFinalizationCancelledException("IMPORT_RUN_NOT_ACTIVE")
		}
		if (connection.status != ConnectionStatus.CONNECTED) {
			throw MailFinalizationCancelledException("CONNECTION_NOT_CONNECTED")
		}
		if (connection.version != collectionConnectionVersion) {
			throw MailFinalizationCancelledException("CONNECTION_CHANGED")
		}
		if (run.requestedBy == ImportRequestedBy.MONITOR && !connection.ongoingSyncConsent) {
			throw MailFinalizationCancelledException("MONITORING_CONSENT_REVOKED")
		}
		return finalizeLocked(run, candidate, now)
	}

	private fun finalizeLocked(
		run: ImportRun,
		candidate: MailCandidate,
		now: Instant,
	): MailFinalizationOutcome {
		reviewStateService.lock(run.userId, now)
		val messageId = candidate.providerMessageId.take(255)
		val ledger = lockOrCreateLedger(run, messageId, candidate.stableProviderMessageKey, now)
		val processKeys = candidate.providerProcessKeys.map(String::trim)
			.filter(String::isNotEmpty).toSortedSet()
		if (ledger.state in TERMINAL_LEDGER_STATES) {
			if (ledger.state == "FINALIZED" && ledger.applicationId != null) {
				bindProcessKeys(run, processKeys, ledger.applicationId, now)
			}
			return MailFinalizationOutcome.DUPLICATE
		}

		val analysis = analyzer.analyze(candidate)
		if (analysis == null) {
			markLedger(run, ledger, "IGNORED", null, now)
			return MailFinalizationOutcome.IGNORED
		}

		val boundApplicationId = boundApplicationId(run, processKeys)
		val mutationId = deterministicMutationId(run, messageId, candidate.stableProviderMessageKey)
		val application: JobApplication
		val reviewMembershipAdded: Boolean
		if (boundApplicationId == null) {
			application = JobApplication.createImported(
				UUID.randomUUID(), run.userId, analysis.company, analysis.position,
				analysis.location, analysis.employmentType, analysis.appliedAt, analysis.stage,
				analysis.highestStageReached, analysis.screeningPassed, analysis.result, true,
				"${candidate.provider.apiValue()} 메일",
				ApplicationSourceType.fromProviderValue(candidate.provider.apiValue()), mutationId, now,
			)
			applicationRepository.saveAndFlush(application)
			reviewMembershipAdded = true
		} else {
			application = applicationRepository.findOwnedLocked(boundApplicationId, run.userId)
				?: throw MailFinalizationException("PROCESS_BINDING_CONFLICT")
			val wasNeedsReview = application.needsReview
			val previousStatus = application.currentStatusLabel()
			application.applyImportedProgress(
				analysis.stage, analysis.highestStageReached, analysis.screeningPassed,
				analysis.result, true, now,
			)
			application.recordImportedMessage(mutationId, now)
			recordChange(
				run.userId, application.id, mutationId, "status", "진행 상태",
				previousStatus, application.currentStatusLabel(), now,
			)
			recordChange(
				run.userId, application.id, mutationId, "needsReview", "검토 상태",
				if (wasNeedsReview) "확인 필요" else "확인 완료", "확인 필요", now,
			)
			reviewMembershipAdded = !wasNeedsReview && application.needsReview
			applicationRepository.saveAndFlush(application)
		}

		mergeSchedule(run.userId, application.id, candidate, analysis, now)
		emailRepository.save(
			ApplicationEmail.create(
				UUID.randomUUID(), run.userId, application.id, run.connectionId,
				candidate.provider.apiValue(), messageId, candidate.subject.take(500),
				candidate.sender.take(320), candidate.receivedAt, analysis.sourceSummary, now,
			),
		)
		activityRepository.save(
			ApplicationActivity.create(
				UUID.randomUUID(), run.userId, application.id, ActivityType.EMAIL,
				"채용 메일을 지원 이력에 반영했습니다",
				"메일 원문은 저장하지 않고 확인한 추출 정보만 반영했습니다.",
				candidate.receivedAt, now,
			),
		)
		bindProcessKeys(run, processKeys, application.id, now)
		if (reviewMembershipAdded) reviewStateService.increment(run.userId, now)
		markLedger(run, ledger, "FINALIZED", application.id, now)
		return MailFinalizationOutcome.FINALIZED
	}

	private fun recordChange(
		userId: UUID,
		applicationId: UUID,
		mutationId: UUID,
		fieldKey: String,
		title: String,
		before: String,
		after: String,
		now: Instant,
	) {
		if (before == after) return
		changeRepository.save(
			ApplicationChange.create(
				UUID.randomUUID(), userId, applicationId, mutationId,
				fieldKey, title, before, after, now,
			),
		)
	}

	private fun lockOrCreateLedger(
		run: ImportRun,
		messageId: String,
		stableKey: String?,
		now: Instant,
	): LedgerRow {
		val rows = jdbcTemplate.query(
			"""
				SELECT id, state, provider_message_id, stable_provider_message_key, application_id
				FROM mail_ingestion_ledger
				WHERE user_id = ? AND connection_id = ?
				  AND (provider_message_id = ? OR stable_provider_message_key = ?)
				ORDER BY id
				FOR UPDATE
			""".trimIndent(),
			{ resultSet, _ ->
				LedgerRow(
					resultSet.getObject("id", UUID::class.java), resultSet.getString("state"),
					resultSet.getString("provider_message_id"),
					resultSet.getString("stable_provider_message_key"),
					resultSet.getObject("application_id", UUID::class.java),
				)
			},
			run.userId, run.connectionId, messageId, stableKey,
		)
		if (rows.size > 1) throw MailFinalizationException("MESSAGE_LEDGER_CONFLICT")
		val existing = rows.singleOrNull()
		if (existing != null) {
			if (stableKey != null && existing.stableKey == null) {
				try {
					jdbcTemplate.update(
						"UPDATE mail_ingestion_ledger SET stable_provider_message_key = ?, updated_at = ? WHERE id = ?",
						stableKey, Timestamp.from(now), existing.id,
					)
				} catch (_: DataIntegrityViolationException) {
					throw MailFinalizationException("MESSAGE_LEDGER_CONFLICT")
				}
			}
			return existing.copy(stableKey = existing.stableKey ?: stableKey)
		}
		return LedgerRow(UUID.randomUUID(), "NEW", messageId, stableKey, null, true)
	}

	private fun boundApplicationId(run: ImportRun, processKeys: Set<String>): UUID? {
		val applicationIds = processKeys.mapNotNull { key ->
			jdbcTemplate.query(
				"""
					SELECT application_id
					FROM provider_process_bindings
					WHERE user_id = ? AND connection_id = ? AND provider_process_key = ?
					FOR UPDATE
				""".trimIndent(),
				{ resultSet, _ -> resultSet.getObject("application_id", UUID::class.java) },
				run.userId, run.connectionId, key.take(PROCESS_KEY_LIMIT),
			).singleOrNull()
		}.toSet()
		if (applicationIds.size > 1) throw MailFinalizationException("PROCESS_BINDING_CONFLICT")
		return applicationIds.singleOrNull()
	}

	private fun bindProcessKeys(run: ImportRun, processKeys: Set<String>, applicationId: UUID, now: Instant) {
		processKeys.forEach { key ->
			jdbcTemplate.update(
				"""
					INSERT INTO provider_process_bindings (
					    user_id, connection_id, provider_process_key, application_id, created_at
					) VALUES (?, ?, ?, ?, ?)
					ON CONFLICT (user_id, connection_id, provider_process_key) DO NOTHING
				""".trimIndent(),
				run.userId, run.connectionId, key.take(PROCESS_KEY_LIMIT), applicationId, Timestamp.from(now),
			)
			val winner = jdbcTemplate.queryForObject(
				"""
					SELECT application_id FROM provider_process_bindings
					WHERE user_id = ? AND connection_id = ? AND provider_process_key = ?
				""".trimIndent(),
				UUID::class.java,
				run.userId, run.connectionId, key.take(PROCESS_KEY_LIMIT),
			)
			if (winner != applicationId) throw MailFinalizationException("PROCESS_BINDING_CONFLICT")
		}
	}

	private fun mergeSchedule(
		userId: UUID,
		applicationId: UUID,
		candidate: MailCandidate,
		analysis: AnalyzedMailCandidate,
		now: Instant,
	) {
		val type = analysis.scheduleType ?: return
		val action = analysis.scheduleAction ?: return
		val scheduledAt = analysis.scheduledAt ?: return
		val schedule = scheduleRepository.findForApplicationLocked(userId, applicationId)
		if (schedule == null) {
			scheduleRepository.save(
				ApplicationSchedule.createImported(
					UUID.randomUUID(), userId, applicationId, type, action, scheduledAt,
					analysis.scheduleEndsAt, candidate.receivedAt, now,
				),
			)
		} else if (schedule.mergeImported(
				type, action, scheduledAt, analysis.scheduleEndsAt, candidate.receivedAt, now,
			)
		) {
			scheduleRepository.save(schedule)
		}
	}

	private fun markLedger(
		run: ImportRun,
		ledger: LedgerRow,
		state: String,
		applicationId: UUID?,
		now: Instant,
	) {
		try {
			if (ledger.isNew) {
				jdbcTemplate.update(
					"""
						INSERT INTO mail_ingestion_ledger (
						    id, user_id, connection_id, provider_message_id,
						    stable_provider_message_key, state, application_id,
						    first_seen_at, updated_at
						) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
					""".trimIndent(),
					ledger.id, run.userId, run.connectionId, ledger.messageId, ledger.stableKey,
					state, applicationId, Timestamp.from(now), Timestamp.from(now),
				)
			} else {
				jdbcTemplate.update(
					"UPDATE mail_ingestion_ledger SET state = ?, application_id = ?, updated_at = ? WHERE id = ?",
					state, applicationId, Timestamp.from(now), ledger.id,
				)
			}
		} catch (_: DataIntegrityViolationException) {
			throw MailFinalizationException("MESSAGE_LEDGER_CONFLICT")
		}
	}

	private fun deterministicMutationId(run: ImportRun, messageId: String, stableKey: String?): UUID =
		UUID.nameUUIDFromBytes(
			"mail-finalize:${run.userId}:${run.connectionId}:${stableKey ?: messageId}"
				.toByteArray(StandardCharsets.UTF_8),
		)

	private data class LedgerRow(
		val id: UUID,
		val state: String,
		val messageId: String,
		val stableKey: String?,
		val applicationId: UUID?,
		val isNew: Boolean = false,
	)

	private companion object {
		val TERMINAL_LEDGER_STATES = setOf("FINALIZED", "IGNORED", "ACCEPTED", "REJECTED")
		const val PROCESS_KEY_LIMIT = 128
	}
}
