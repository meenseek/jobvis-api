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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Component
class ImportRunWorker(
	private val claimService: ImportRunClaimService,
	private val runRepository: ImportRunRepository,
	private val connectionRepository: ExternalConnectionRepository,
	private val mailCollector: MailCollector,
	private val completionService: ImportRunCompletionService,
	@Value("\${jobvis.import.worker-concurrency:2}") private val workerConcurrency: Int,
	@Value("\${jobvis.import.worker-shutdown-grace:PT20S}") private val workerShutdownGrace: Duration,
	@Value("\${jobvis.import.heartbeat-interval:PT20S}") private val heartbeatInterval: Duration,
	@Value("\${spring.datasource.hikari.maximum-pool-size:10}") private val databasePoolSize: Int,
) {
	private val workerThreadCounter = AtomicInteger()
	private val heartbeatThreadCounter = AtomicInteger()
	private val workerSlots: Semaphore
	private val workerExecutor: java.util.concurrent.ExecutorService
	private val heartbeatExecutor: java.util.concurrent.ScheduledExecutorService
	private val acceptingWork = AtomicBoolean(true)
	private val forceStopping = AtomicBoolean(false)

	init {
		require(workerConcurrency in 1..32) { "jobvis.import.worker-concurrency는 1~32여야 합니다." }
		require(workerConcurrency <= databasePoolSize) {
			"jobvis.import.worker-concurrency는 DB connection pool 크기 이하여야 합니다."
		}
		require(heartbeatInterval >= MIN_HEARTBEAT_INTERVAL &&
			heartbeatInterval <= MAX_HEARTBEAT_INTERVAL
		) {
			"jobvis.import.heartbeat-interval은 1초 이상 30초 이하여야 합니다."
		}
		require(!workerShutdownGrace.isNegative && workerShutdownGrace <= MAX_WORKER_SHUTDOWN_GRACE) {
			"jobvis.import.worker-shutdown-grace는 0초~5분이어야 합니다."
		}
		workerSlots = Semaphore(workerConcurrency)
		workerExecutor = Executors.newFixedThreadPool(workerConcurrency) { runnable ->
			Thread(runnable, "jobvis-import-worker-${workerThreadCounter.incrementAndGet()}").apply { isDaemon = true }
		}
		heartbeatExecutor = Executors.newScheduledThreadPool(workerConcurrency) { runnable ->
			Thread(runnable, "jobvis-import-heartbeat-${heartbeatThreadCounter.incrementAndGet()}").apply {
				isDaemon = true
			}
		}
	}

	@Scheduled(fixedDelayString = "\${jobvis.import.poll-delay:PT5S}")
	fun poll() {
		repeat(MAX_RUNS_PER_POLL) {
			if (!dispatchOnce()) return
		}
	}

	internal fun dispatchOnce(): Boolean {
		if (!acceptingWork.get()) return false
		if (!workerSlots.tryAcquire()) return false
		val claim = try {
			claimService.claimNext()
		} catch (exception: Exception) {
			workerSlots.release()
			throw exception
		}
		if (claim == null) {
			workerSlots.release()
			return false
		}
		return try {
			workerExecutor.execute {
				try {
					processClaim(claim)
				} finally {
					workerSlots.release()
				}
			}
			true
		} catch (_: RejectedExecutionException) {
			workerSlots.release()
			claimService.releaseForRetry(claim)
			false
		}
	}

	fun runOnce(): Boolean {
		val claim = claimService.claimNext() ?: return false
		processClaim(claim)
		return true
	}

	internal fun processClaim(claim: ClaimedImportRun) {
		if (forceStopping.get()) return
		val run = runRepository.findById(claim.runId).orElse(null) ?: return
		val connection = connectionRepository.findOwned(run.connectionId, run.userId)
		if (forceStopping.get()) return
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
			if (forceStopping.get()) return
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
			if (forceStopping.get()) return
			completionService.complete(claim, collection)
		} catch (exception: MailCollectionException) {
			if (forceStopping.get()) return
			completionService.fail(
				claim, exception.errorCode, exception.disposition,
				exception.connectionVersion ?: attemptConnectionVersion,
			)
		} catch (exception: ExternalConnectionAuthorizationException) {
			if (forceStopping.get()) return
			completionService.fail(
				claim, "EXTERNAL_REAUTHORIZATION_REQUIRED", MailFailureDisposition.REAUTHORIZATION_REQUIRED,
				exception.connectionVersion ?: attemptConnectionVersion,
			)
		} catch (_: ServiceUnavailableException) {
			if (forceStopping.get()) return
			completionService.fail(
				claim, "IMPORT_SERVICE_TEMPORARILY_UNAVAILABLE", MailFailureDisposition.TRANSIENT,
				attemptConnectionVersion,
			)
		} catch (_: Exception) {
			if (forceStopping.get()) return
			completionService.fail(
				claim, "IMPORT_PROCESSING_FAILED", MailFailureDisposition.RUN_ONLY, attemptConnectionVersion,
			)
		} finally {
			heartbeat.cancel(false)
		}
	}

	internal fun startHeartbeat(claim: ClaimedImportRun): ScheduledFuture<*> = heartbeatExecutor.scheduleAtFixedRate(
		{ runCatching { claimService.heartbeat(claim) } },
		heartbeatInterval.toNanos(),
		heartbeatInterval.toNanos(),
		TimeUnit.NANOSECONDS,
	)

	@PreDestroy
	fun shutdownExecutors() {
		acceptingWork.set(false)
		workerExecutor.shutdown()
		val terminated = try {
			workerExecutor.awaitTermination(workerShutdownGrace.toMillis(), TimeUnit.MILLISECONDS)
		} catch (_: InterruptedException) {
			Thread.currentThread().interrupt()
			false
		}
		if (!terminated) {
			forceStopping.set(true)
			workerExecutor.shutdownNow()
			try {
				workerExecutor.awaitTermination(INTERRUPT_SETTLE_SECONDS, TimeUnit.SECONDS)
			} catch (_: InterruptedException) {
				Thread.currentThread().interrupt()
			}
		}
		heartbeatExecutor.shutdownNow()
	}

	private companion object {
		const val MAX_RUNS_PER_POLL = 5
		const val INTERRUPT_SETTLE_SECONDS = 1L
		val MIN_HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(1)
		val MAX_HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(30)
		val MAX_WORKER_SHUTDOWN_GRACE: Duration = Duration.ofMinutes(5)
	}
}

data class ClaimedImportRun(val runId: UUID, val leaseOwner: UUID)

@Service
class ImportRunClaimService(
	private val jdbcTemplate: JdbcTemplate,
) {
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun claimNext(): ClaimedImportRun? {
		recoverExpired()
		val leaseOwner = UUID.randomUUID()
		return jdbcTemplate.query(
		"""
			UPDATE import_runs
			SET status = 'RUNNING', started_at = clock_timestamp(), updated_at = clock_timestamp(),
			    lease_owner = ?, lease_expires_at = clock_timestamp() + interval '2 minutes',
			    heartbeat_at = clock_timestamp(),
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
		leaseOwner,
	).firstOrNull()
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	open fun heartbeat(claim: ClaimedImportRun): Boolean = jdbcTemplate.update(
		"""
			UPDATE import_runs
			SET heartbeat_at = clock_timestamp(),
			    lease_expires_at = clock_timestamp() + interval '2 minutes',
			    updated_at = clock_timestamp()
			WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
		""".trimIndent(),
		claim.runId, claim.leaseOwner,
	) == 1

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	fun releaseForRetry(claim: ClaimedImportRun): Boolean = jdbcTemplate.update(
		"""
			UPDATE import_runs
			SET status = 'QUEUED', started_at = NULL, updated_at = clock_timestamp(),
			    lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL,
			    attempt_count = GREATEST(attempt_count - 1, 0)
			WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
		""".trimIndent(),
		claim.runId, claim.leaseOwner,
	) == 1

	private fun recoverExpired() {
		jdbcTemplate.update(
			"""
				UPDATE import_runs
				SET status = 'FAILED', error_code = 'LEASE_EXPIRED',
				    completed_at = clock_timestamp(), updated_at = clock_timestamp(),
				    lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL
				WHERE status = 'RUNNING' AND lease_expires_at <= clock_timestamp()
				  AND attempt_count >= ?
			""".trimIndent(),
			MAX_ATTEMPTS,
		)
		jdbcTemplate.update(
			"""
				UPDATE import_runs
				SET status = 'QUEUED', started_at = NULL, updated_at = clock_timestamp(),
				    lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL
				WHERE status = 'RUNNING' AND lease_expires_at <= clock_timestamp()
				  AND attempt_count < ?
			""".trimIndent(),
			MAX_ATTEMPTS,
		)
	}

	private companion object {
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
	fun purgeExpired(): Int {
		val now = Instant.now(clock)
		val deletedRuns = jdbcTemplate.update(
			"""
				DELETE FROM import_runs
				WHERE purge_after <= ?
				  AND status IN ('COMPLETED', 'FAILED', 'CANCELLED')
			""".trimIndent(),
			Timestamp.from(now),
		)
		purgeExpiredGmailQuotaGates()
		return deletedRuns
	}

	internal fun purgeExpiredGmailQuotaGates(): Int = jdbcTemplate.update(
			"""
				DELETE FROM gmail_quota_gates
				WHERE updated_at <= clock_timestamp() - interval '7 days'
				  AND next_permit_at <= clock_timestamp()
				  AND blocked_until <= clock_timestamp()
			""".trimIndent(),
		)
}
