package com.meenseek.jobvis.imports

import com.meenseek.jobvis.connection.ConnectionStatus
import com.meenseek.jobvis.connection.ExternalConnectionRepository
import com.meenseek.jobvis.common.ExternalConnectionAuthorizationException
import com.meenseek.jobvis.common.BusinessTime
import com.meenseek.jobvis.common.ServiceUnavailableException
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import jakarta.annotation.PreDestroy
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Component
class ImportRunWorker(
	private val claimService: ImportRunClaimService,
	private val runRepository: ImportRunRepository,
	private val connectionRepository: ExternalConnectionRepository,
	private val mailCollector: MailCollector,
	private val completionService: ImportRunCompletionService,
	private val clock: Clock,
) {
	private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
		Thread(runnable, "jobvis-import-heartbeat").apply { isDaemon = true }
	}

	@Scheduled(fixedDelayString = "\${jobvis.import.poll-delay:PT5S}")
	fun poll() {
		repeat(MAX_RUNS_PER_POLL) {
			if (!runOnce()) return
		}
	}

	fun runOnce(): Boolean {
		val claim = claimService.claimNext(Instant.now(clock)) ?: return false
		processClaim(claim)
		return true
	}

	internal fun processClaim(claim: ClaimedImportRun) {
		val run = runRepository.findById(claim.runId).orElse(null) ?: return
		val connection = connectionRepository.findOwned(run.connectionId, run.userId)
		if (connection == null || connection.status != ConnectionStatus.CONNECTED) {
			completionService.fail(
				claim, "CONNECTION_NOT_CONNECTED", MailFailureDisposition.REAUTHORIZATION_REQUIRED,
				connection?.version,
			)
			return
		}
		if (connection.version != run.connectionVersion) {
			completionService.cancel(claim, "CONNECTION_CHANGED")
			return
		}
		if (run.requestedBy == ImportRequestedBy.MONITOR && !connection.ongoingSyncConsent) {
			completionService.cancel(claim, "MONITORING_CONSENT_REVOKED")
			return
		}
		val heartbeat = startHeartbeat(claim)
		var attemptConnectionVersion = connection.version
		try {
			val latestConnection = connectionRepository.findOwned(run.connectionId, run.userId)
			if (run.requestedBy == ImportRequestedBy.MONITOR && latestConnection?.ongoingSyncConsent != true) {
				completionService.cancel(claim, "MONITORING_CONSENT_REVOKED")
				return
			}
			val activeConnection = latestConnection ?: connection
			attemptConnectionVersion = activeConnection.version
			if (activeConnection.version != run.connectionVersion) {
				completionService.cancel(claim, "CONNECTION_CHANGED")
				return
			}
			val collection = mailCollector.collect(activeConnection, run.dateFrom, run.dateTo)
			completionService.complete(claim, collection)
		} catch (exception: MailCollectionException) {
			completionService.fail(
				claim, exception.errorCode, exception.disposition,
				exception.connectionVersion ?: attemptConnectionVersion,
			)
		} catch (exception: ExternalConnectionAuthorizationException) {
			completionService.fail(
				claim, "EXTERNAL_REAUTHORIZATION_REQUIRED", MailFailureDisposition.REAUTHORIZATION_REQUIRED,
				exception.connectionVersion ?: attemptConnectionVersion,
			)
		} catch (_: ServiceUnavailableException) {
			completionService.fail(
				claim, "IMPORT_SERVICE_TEMPORARILY_UNAVAILABLE", MailFailureDisposition.TRANSIENT,
				attemptConnectionVersion,
			)
		} catch (_: Exception) {
			completionService.fail(
				claim, "IMPORT_PROCESSING_FAILED", MailFailureDisposition.RUN_ONLY, attemptConnectionVersion,
			)
		} finally {
			heartbeat.cancel(false)
		}
	}

	private fun startHeartbeat(claim: ClaimedImportRun): ScheduledFuture<*> = heartbeatExecutor.scheduleAtFixedRate(
		{ runCatching { claimService.heartbeat(claim, Instant.now(clock)) } },
		HEARTBEAT_INTERVAL_SECONDS,
		HEARTBEAT_INTERVAL_SECONDS,
		TimeUnit.SECONDS,
	)

	@PreDestroy
	fun shutdownHeartbeatExecutor() {
		heartbeatExecutor.shutdownNow()
	}

	private companion object {
		const val MAX_RUNS_PER_POLL = 5
		const val HEARTBEAT_INTERVAL_SECONDS = 20L
	}
}

data class ClaimedImportRun(val runId: UUID, val leaseOwner: UUID)

@Service
class ImportRunClaimService(
	private val jdbcTemplate: JdbcTemplate,
) {
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun claimNext(now: Instant): ClaimedImportRun? {
		recoverExpired(now)
		val leaseOwner = UUID.randomUUID()
		return jdbcTemplate.query(
		"""
			UPDATE import_runs
			SET status = 'RUNNING', started_at = ?, updated_at = ?,
			    lease_owner = ?, lease_expires_at = ?, heartbeat_at = ?,
			    attempt_count = attempt_count + 1
			WHERE id = (
			    SELECT id
			    FROM import_runs
			    WHERE status = 'QUEUED'
			    ORDER BY created_at, id
			    FOR UPDATE SKIP LOCKED
			    LIMIT 1
			)
			RETURNING id, lease_owner
		""".trimIndent(),
		{ resultSet, _ ->
			ClaimedImportRun(
				resultSet.getObject("id", UUID::class.java),
				resultSet.getObject("lease_owner", UUID::class.java),
			)
		},
		Timestamp.from(now),
		Timestamp.from(now),
		leaseOwner,
		Timestamp.from(now.plus(LEASE_DURATION)),
		Timestamp.from(now),
	).firstOrNull()
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun heartbeat(claim: ClaimedImportRun, now: Instant): Boolean = jdbcTemplate.update(
		"""
			UPDATE import_runs
			SET heartbeat_at = ?, lease_expires_at = ?, updated_at = ?
			WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
		""".trimIndent(),
		Timestamp.from(now), Timestamp.from(now.plus(LEASE_DURATION)), Timestamp.from(now),
		claim.runId, claim.leaseOwner,
	) == 1

	private fun recoverExpired(now: Instant) {
		jdbcTemplate.update(
			"""
				UPDATE import_runs
				SET status = 'FAILED', error_code = 'LEASE_EXPIRED', completed_at = ?, updated_at = ?,
				    lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL
				WHERE status = 'RUNNING' AND lease_expires_at <= ? AND attempt_count >= ?
			""".trimIndent(),
			Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), MAX_ATTEMPTS,
		)
		jdbcTemplate.update(
			"""
				UPDATE import_runs
				SET status = 'QUEUED', started_at = NULL, updated_at = ?,
				    lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL
				WHERE status = 'RUNNING' AND lease_expires_at <= ? AND attempt_count < ?
			""".trimIndent(),
			Timestamp.from(now), Timestamp.from(now), MAX_ATTEMPTS,
		)
	}

	private companion object {
		val LEASE_DURATION: Duration = Duration.ofMinutes(2)
		const val MAX_ATTEMPTS = 3
	}
}

@Service
class ImportRunCompletionService(
	private val runRepository: ImportRunRepository,
	private val draftRepository: ImportDraftRepository,
	private val connectionRepository: ExternalConnectionRepository,
	private val analyzer: RecruitmentMailAnalyzer,
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
	@Value("\${jobvis.import.retention:PT720H}") private val retention: Duration,
) {
	@Transactional
	fun complete(claim: ClaimedImportRun, collection: MailCollectionResult) {
		val snapshot = runRepository.findById(claim.runId).orElse(null) ?: return
		val connection = connectionRepository.findOwnedLocked(snapshot.connectionId, snapshot.userId)
			?: run {
				val run = runRepository.findClaimedLocked(claim.runId, claim.leaseOwner) ?: return
				run.fail("CONNECTION_NOT_FOUND", Instant.now(clock))
				runRepository.save(run)
				return
			}
		val run = runRepository.findClaimedLocked(claim.runId, claim.leaseOwner) ?: return
		if (run.status != ImportRunStatus.RUNNING) return
		val now = Instant.now(clock)
		if (connection.status != ConnectionStatus.CONNECTED) {
			run.cancel("CONNECTION_NOT_CONNECTED", now)
			runRepository.save(run)
			return
		}
		if (connection.version != collection.connectionVersion) {
			run.cancel("CONNECTION_CHANGED", now)
			runRepository.save(run)
			return
		}
		if (run.requestedBy == ImportRequestedBy.MONITOR && !connection.ongoingSyncConsent) {
			run.cancel("MONITORING_CONSENT_REVOKED", now)
			runRepository.save(run)
			return
		}
		val seenMessageIds = mutableSetOf<String>()
		var duplicateCount = 0
		val drafts = collection.candidates.mapNotNull { candidate ->
			val analysis = analyzer.analyze(candidate) ?: return@mapNotNull null
			val messageId = candidate.providerMessageId.take(255)
			if (!seenMessageIds.add(messageId) || !reserveMessage(run, messageId, now)) {
				duplicateCount++
				return@mapNotNull null
			}
			ImportDraft.pending(
				UUID.randomUUID(), run.userId, run.id, run.connectionId,
				candidate.copy(providerMessageId = messageId), analysis, now, now.plus(retention),
			)
		}
		draftRepository.saveAll(drafts)
		draftRepository.flush()
		run.complete(collection.candidates.size, drafts.size, duplicateCount, now)
		val rangeCheckpoint = run.dateTo.plusDays(1).atStartOfDay(BusinessTime.SEOUL).toInstant()
		connection.markSynced(minOf(now, rangeCheckpoint), now.plusSeconds(15 * 60), now)
		runRepository.save(run)
		connectionRepository.save(connection)
	}

	private fun reserveMessage(run: ImportRun, messageId: String, now: Instant): Boolean =
		jdbcTemplate.update(
			"""
				INSERT INTO mail_ingestion_ledger (
				    id, user_id, connection_id, provider_message_id, state, first_seen_at, updated_at
				) VALUES (?, ?, ?, ?, 'DRAFTED', ?, ?)
				ON CONFLICT (user_id, connection_id, provider_message_id) DO NOTHING
			""".trimIndent(),
			UUID.randomUUID(), run.userId, run.connectionId, messageId,
			Timestamp.from(now), Timestamp.from(now),
		) == 1

	@Transactional
	fun fail(
		claim: ClaimedImportRun,
		errorCode: String,
		disposition: MailFailureDisposition,
		expectedConnectionVersion: Long? = null,
	) {
		val snapshot = runRepository.findById(claim.runId).orElse(null) ?: return
		val connection = connectionRepository.findOwnedLocked(snapshot.connectionId, snapshot.userId)
		val run = runRepository.findClaimedLocked(claim.runId, claim.leaseOwner) ?: return
		if (run.status != ImportRunStatus.RUNNING) return
		val now = Instant.now(clock)
		run.fail(errorCode, now)
		connection?.let { connection ->
			if (connection.status != ConnectionStatus.REVOKED &&
				(expectedConnectionVersion == null || connection.version == expectedConnectionVersion)
			) {
				when (disposition) {
					MailFailureDisposition.REAUTHORIZATION_REQUIRED -> connection.markError(errorCode, true, now)
					MailFailureDisposition.TRANSIENT -> connection.markTransientError(errorCode, now.plusSeconds(15 * 60), now)
					MailFailureDisposition.RUN_ONLY -> if (run.requestedBy == ImportRequestedBy.MONITOR) {
						connection.pauseMonitoringAfterRunError(errorCode, now)
					}
					MailFailureDisposition.CONNECTION_ERROR -> connection.markError(errorCode, false, now)
				}
				connectionRepository.save(connection)
			}
		}
		runRepository.save(run)
	}

	@Transactional
	fun cancel(claim: ClaimedImportRun, errorCode: String) {
		val run = runRepository.findClaimedLocked(claim.runId, claim.leaseOwner) ?: return
		if (run.status != ImportRunStatus.RUNNING) return
		run.cancel(errorCode, Instant.now(clock))
		runRepository.save(run)
	}
}

@Component
class ImportRetentionWorker(
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
) {
	@Scheduled(fixedDelayString = "\${jobvis.import.cleanup-delay:PT1H}")
	@Transactional
	fun purgeExpired(): Int = jdbcTemplate.update(
		"""
			DELETE FROM import_runs
			WHERE purge_after <= ?
			  AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')
		""".trimIndent(),
		Timestamp.from(Instant.now(clock)),
	)
}
