package com.meenseek.jobvis.imports

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ConflictException
import com.meenseek.jobvis.common.NotFoundException
import com.meenseek.jobvis.application.RequestFingerprint
import com.meenseek.jobvis.connection.ConnectionProvider
import com.meenseek.jobvis.connection.ConnectionStatus
import com.meenseek.jobvis.connection.ExternalConnectionRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class NaverLedgerReconciliationFile(
	val operationId: UUID,
	val connectionId: UUID,
	val expectedLedgerCount: Long,
	val expectedStateCounts: Map<String, Long>,
	val reconciledBy: String,
	val entries: List<NaverLedgerReconciliationEntry>,
)

data class NaverLedgerReconciliationEntry(
	val ledgerId: UUID,
	val disposition: String,
	val stableProviderMessageKey: String?,
	val evidenceType: String,
	val evidenceReference: String,
)

data class NaverLedgerReconciliationResult(
	val connectionId: UUID,
	val ledgerCount: Long,
	val stableKeyCount: Long,
	val verifiedUidOnlyCount: Long,
)

@Service
class NaverLedgerReconciliationService(
	private val connectionRepository: ExternalConnectionRepository,
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
) {
	@Transactional
	fun reconcile(request: NaverLedgerReconciliationFile): NaverLedgerReconciliationResult {
		jdbcTemplate.lockMailFinalizationRollout()
		val fingerprint = fingerprint(request)
		val connection = connectionRepository.findById(request.connectionId).orElse(null)
			?: throw NotFoundException("Naver 연결을 찾을 수 없습니다.")
		val locked = connectionRepository.findOwnedLocked(connection.id, connection.userId)
			?: throw NotFoundException("Naver 연결을 찾을 수 없습니다.")
		jdbcTemplate.update(
			"""
				INSERT INTO naver_ledger_reconciliation_runs (
				    operation_id, user_id, connection_id, request_fingerprint, created_at
				) VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (operation_id) DO NOTHING
			""".trimIndent(),
			request.operationId, locked.userId, locked.id, fingerprint, Timestamp.from(Instant.now(clock)),
		)
		val replay = jdbcTemplate.query(
			"""
				SELECT request_fingerprint, ledger_count, stable_key_count, verified_uid_only_count, completed_at
				FROM naver_ledger_reconciliation_runs
				WHERE operation_id = ?
				FOR UPDATE
			""".trimIndent(),
			{ row, _ ->
				ReconciliationRun(
					row.getString("request_fingerprint"),
					(row.getObject("ledger_count") as Number?)?.toLong(),
					(row.getObject("stable_key_count") as Number?)?.toLong(),
					(row.getObject("verified_uid_only_count") as Number?)?.toLong(),
					row.getTimestamp("completed_at")?.toInstant(),
				)
			},
			request.operationId,
		).single()
		if (replay.fingerprint != fingerprint) {
			throw ConflictException("이미 다른 Naver reconciliation에 사용된 operationId입니다.")
		}
		if (replay.completedAt != null) {
			return NaverLedgerReconciliationResult(
				locked.id, requireNotNull(replay.ledgerCount), requireNotNull(replay.stableKeyCount),
				requireNotNull(replay.verifiedUidOnlyCount),
			)
		}
		if (locked.provider != ConnectionProvider.NAVER || locked.status != ConnectionStatus.ERROR ||
			locked.lastErrorCode != NAVER_MIGRATION_ERROR || locked.encryptedAppPassword == null
		) {
			throw ConflictException("Naver ledger migration 대기 연결만 정리할 수 있습니다.")
		}
		val reconciledBy = request.reconciledBy.trim()
		if (reconciledBy.isEmpty() || reconciledBy.length > 160) {
			throw BadRequestException("reconciledBy는 1~160자여야 합니다.")
		}
		val rows = jdbcTemplate.query(
			"""
				SELECT id, state, stable_provider_message_key
				FROM mail_ingestion_ledger
				WHERE user_id = ? AND connection_id = ?
				ORDER BY id
				FOR UPDATE
			""".trimIndent(),
			{ row, _ ->
				LedgerSnapshot(
					row.getObject("id", UUID::class.java), row.getString("state"),
					row.getString("stable_provider_message_key"),
				)
			},
			locked.userId, locked.id,
		)
		if (rows.size.toLong() != request.expectedLedgerCount) {
			throw ConflictException("Naver ledger row 수가 증빙 생성 시점과 다릅니다.")
		}
		val actualStateCounts = rows.groupingBy(LedgerSnapshot::state).eachCount()
			.mapValues { it.value.toLong() }
		val expectedStateCounts = request.expectedStateCounts.mapKeys { it.key.uppercase(Locale.ROOT) }
			.filterValues { it != 0L }
		if (actualStateCounts != expectedStateCounts) {
			throw ConflictException("Naver ledger 상태별 건수가 증빙 생성 시점과 다릅니다.")
		}
		val unresolved = rows.filter { it.stableKey == null }
		if (request.entries.map { it.ledgerId }.toSet().size != request.entries.size ||
			request.entries.map { it.ledgerId }.toSet() != unresolved.map { it.id }.toSet()
		) {
			throw BadRequestException("stable key가 없는 모든 ledger를 정확히 한 번씩 입력해야 합니다.")
		}

		val now = Instant.now(clock)
		request.entries.sortedBy { it.ledgerId }.forEach { entry ->
			val disposition = entry.disposition.trim().uppercase(Locale.ROOT)
			val evidenceType = entry.evidenceType.trim().uppercase(Locale.ROOT)
			if (disposition !in DISPOSITIONS || evidenceType !in EVIDENCE_TYPES) {
				throw BadRequestException("Naver reconciliation disposition 또는 evidenceType이 올바르지 않습니다.")
			}
			val evidenceReference = entry.evidenceReference.trim()
			if (evidenceReference.isEmpty() || evidenceReference.length > 500) {
				throw BadRequestException("evidenceReference는 1~500자여야 합니다.")
			}
			val stableKey = entry.stableProviderMessageKey?.trim()?.lowercase(Locale.ROOT)
			if ((disposition == "STABLE_KEY") != (stableKey != null) ||
				(stableKey != null && !SHA_256.matches(stableKey))
			) {
				throw BadRequestException("STABLE_KEY는 SHA-256 stableProviderMessageKey가 필요합니다.")
			}
			if (stableKey != null) {
				val collision = jdbcTemplate.queryForObject(
					"""
						SELECT EXISTS (
						    SELECT 1 FROM mail_ingestion_ledger
						    WHERE user_id = ? AND connection_id = ?
						      AND stable_provider_message_key = ? AND id <> ?
						)
					""".trimIndent(),
					Boolean::class.java, locked.userId, locked.id, stableKey, entry.ledgerId,
				) == true
				if (collision) throw ConflictException("Naver stable message key collision이 있습니다.")
				jdbcTemplate.update(
					"UPDATE mail_ingestion_ledger SET stable_provider_message_key = ?, updated_at = ? WHERE id = ?",
					stableKey, Timestamp.from(now), entry.ledgerId,
				)
			}
			jdbcTemplate.update(
				"""
					INSERT INTO naver_ledger_reconciliation_audits (
					    id, user_id, connection_id, operation_id, ledger_id, disposition,
					    stable_provider_message_key, evidence_type, evidence_reference,
					    reconciled_by, reconciled_at
					) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""".trimIndent(),
				UUID.randomUUID(), locked.userId, locked.id, request.operationId, entry.ledgerId, disposition,
				stableKey, evidenceType, evidenceReference, reconciledBy, Timestamp.from(now),
			)
		}

		val remaining = jdbcTemplate.queryForObject(
			"""
				SELECT count(*)
				FROM mail_ingestion_ledger ledger
				LEFT JOIN naver_ledger_reconciliation_audits audit ON audit.ledger_id = ledger.id
				WHERE ledger.user_id = ? AND ledger.connection_id = ?
				  AND ledger.stable_provider_message_key IS NULL
				  AND (audit.disposition IS NULL OR audit.disposition <> 'VERIFIED_UID_ONLY')
			""".trimIndent(),
			Long::class.java, locked.userId, locked.id,
		) ?: 0
		val finalRows = jdbcTemplate.queryForObject(
			"SELECT count(*) FROM mail_ingestion_ledger WHERE user_id = ? AND connection_id = ?",
			Long::class.java, locked.userId, locked.id,
		) ?: 0
		if (remaining != 0L || finalRows != request.expectedLedgerCount) {
			throw ConflictException("Naver ledger reconciliation 검증을 완료하지 못했습니다.")
		}
		locked.completeNaverLedgerMigration(now)
		connectionRepository.saveAndFlush(locked)
		val stableCount = rows.count { it.stableKey != null }.toLong() +
			request.entries.count { it.disposition.equals("STABLE_KEY", true) }
		val result = NaverLedgerReconciliationResult(
			locked.id, finalRows, stableCount,
			request.entries.count { it.disposition.equals("VERIFIED_UID_ONLY", true) }.toLong(),
		)
		jdbcTemplate.update(
			"""
				UPDATE naver_ledger_reconciliation_runs
				SET ledger_count = ?, stable_key_count = ?, verified_uid_only_count = ?, completed_at = ?
				WHERE operation_id = ? AND completed_at IS NULL
			""".trimIndent(),
			result.ledgerCount, result.stableKeyCount, result.verifiedUidOnlyCount,
			Timestamp.from(now), request.operationId,
		)
		return result
	}

	private data class LedgerSnapshot(val id: UUID, val state: String, val stableKey: String?)
	private data class ReconciliationRun(
		val fingerprint: String,
		val ledgerCount: Long?,
		val stableKeyCount: Long?,
		val verifiedUidOnlyCount: Long?,
		val completedAt: Instant?,
	)

	private fun fingerprint(request: NaverLedgerReconciliationFile): String {
		val entries = request.entries.sortedBy { it.ledgerId }.joinToString("|") { entry ->
			listOf(
				entry.ledgerId, entry.disposition.trim().uppercase(Locale.ROOT),
				entry.stableProviderMessageKey?.trim()?.lowercase(Locale.ROOT).orEmpty(),
				entry.evidenceType.trim().uppercase(Locale.ROOT), entry.evidenceReference.trim(),
			).joinToString(":")
		}
		val states = request.expectedStateCounts.mapKeys { it.key.uppercase(Locale.ROOT) }
			.toSortedMap().entries.joinToString("|") { "${it.key}:${it.value}" }
		return RequestFingerprint.of(
			"NAVER_LEDGER_RECONCILIATION", request.connectionId, request.expectedLedgerCount,
			states, request.reconciledBy.trim(), entries,
		)
	}

	private companion object {
		const val NAVER_MIGRATION_ERROR = "NAVER_LEDGER_MIGRATION_REQUIRED"
		val SHA_256 = Regex("^[0-9a-f]{64}$")
		val DISPOSITIONS = setOf("STABLE_KEY", "VERIFIED_UID_ONLY")
		val EVIDENCE_TYPES = setOf("PROVIDER_REFETCH", "PROVIDER_EXPORT", "USER_CONFIRMED")
	}
}

@Component
@ConditionalOnProperty(
	name = ["jobvis.import.naver-reconciliation-enabled"], havingValue = "true", matchIfMissing = false,
)
class NaverLedgerReconciliationRunner(
	private val objectMapper: ObjectMapper,
	private val service: NaverLedgerReconciliationService,
	@Value("\${jobvis.import.naver-reconciliation-file}") private val file: String,
) : ApplicationRunner {
	override fun run(args: ApplicationArguments) {
		val request = objectMapper.readValue(
			Files.readString(Path.of(file)), NaverLedgerReconciliationFile::class.java,
		)
		val result = service.reconcile(request)
		logger.info(
			"Naver ledger reconciliation 완료. connectionId={}, ledgerCount={}, stableKeyCount={}, uidOnlyCount={}",
			result.connectionId, result.ledgerCount, result.stableKeyCount, result.verifiedUidOnlyCount,
		)
	}

	private companion object {
		val logger = LoggerFactory.getLogger(NaverLedgerReconciliationRunner::class.java)
	}
}
