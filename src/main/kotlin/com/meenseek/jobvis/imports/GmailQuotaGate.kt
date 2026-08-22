package com.meenseek.jobvis.imports

import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

@Component
class GmailQuotaGate(
	private val jdbcTemplate: JdbcTemplate,
) {
	fun accountKey(accountEmail: String): String {
		val normalized = accountEmail.trim().lowercase(Locale.ROOT)
		val digest = MessageDigest.getInstance("SHA-256")
			.digest(normalized.toByteArray(StandardCharsets.UTF_8))
		return HexFormat.of().formatHex(digest)
	}

	fun awaitPermit(accountKey: String, maxWaitMillis: Long): Boolean {
		require(maxWaitMillis in 0..MAX_INLINE_WAIT_MILLIS) {
			"Gmail quota 인프로세스 대기 상한은 0ms~60초여야 합니다."
		}
		val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMillis)
		var waitMillis = reservePermitWaitMillis(accountKey, remainingBudgetMillis(deadlineNanos)) ?: return false
		if (deadlineExpired(deadlineNanos)) return false
		while (true) {
			if (!waitWithinBudget(waitMillis, deadlineNanos)) return false
			val blockMillis = remainingBlockMillis(accountKey)
			if (deadlineExpired(deadlineNanos)) return false
			if (blockMillis <= 0) return !deadlineExpired(deadlineNanos)
			if (!waitWithinBudget(blockMillis, deadlineNanos)) return false
			waitMillis = reservePermitWaitMillis(accountKey, remainingBudgetMillis(deadlineNanos)) ?: return false
			if (deadlineExpired(deadlineNanos)) return false
		}
	}

	private fun remainingBudgetMillis(deadlineNanos: Long): Long =
		TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0))

	private fun deadlineExpired(deadlineNanos: Long): Boolean = System.nanoTime() >= deadlineNanos

	private fun waitWithinBudget(waitMillis: Long, deadlineNanos: Long): Boolean {
		if (waitMillis <= 0) return true
		val remainingNanos = deadlineNanos - System.nanoTime()
		if (remainingNanos <= 0 || TimeUnit.MILLISECONDS.toNanos(waitMillis) > remainingNanos) return false
		Thread.sleep(waitMillis)
		return !deadlineExpired(deadlineNanos)
	}

	fun block(accountKey: String, delayMillis: Long) = block(accountKey, delayMillis, null)

	internal fun block(accountKey: String, delayMillis: Long, retryAt: Instant?) {
		require(delayMillis in 1..MAX_BLOCK_MILLIS) { "Gmail quota 차단 시간은 1ms~24시간이어야 합니다." }
		val retryTimestamp = (retryAt ?: Instant.EPOCH).toString()
		jdbcTemplate.update(
			"""
				INSERT INTO gmail_quota_gates (account_key, next_permit_at, blocked_until, updated_at)
				VALUES (
				    ?,
				    clock_timestamp(),
				    GREATEST(
				        clock_timestamp() + (CAST(? AS double precision) * interval '1 millisecond'),
				        LEAST(CAST(? AS timestamptz), clock_timestamp() + interval '24 hours')
				    ),
				    clock_timestamp()
				)
				ON CONFLICT (account_key) DO UPDATE
				SET blocked_until = GREATEST(
				        gmail_quota_gates.blocked_until,
				        clock_timestamp() + (CAST(? AS double precision) * interval '1 millisecond'),
				        LEAST(CAST(? AS timestamptz), clock_timestamp() + interval '24 hours')
				    ),
				    updated_at = clock_timestamp()
			""".trimIndent(),
			accountKey,
			delayMillis,
			retryTimestamp,
			delayMillis,
			retryTimestamp,
		)
	}

	internal fun reservePermitWaitMillis(accountKey: String, maxWaitMillis: Long): Long? {
		require(maxWaitMillis in 0..MAX_INLINE_WAIT_MILLIS) {
			"Gmail quota permit 예약 예산은 0ms~60초여야 합니다."
		}
		return try {
			jdbcTemplate.query(
		"""
			WITH lock_limit AS MATERIALIZED (
			    SELECT set_config(
			        'lock_timeout',
			        GREATEST(1, CAST(? AS bigint))::text || 'ms',
			        true
			    )
			),
			reserved AS (
			    INSERT INTO gmail_quota_gates (account_key, next_permit_at, blocked_until, updated_at)
			    SELECT
			        ?,
			        clock_timestamp() + interval '250 milliseconds',
			        clock_timestamp(),
			        clock_timestamp()
			    FROM lock_limit
			    ON CONFLICT (account_key) DO UPDATE
			    SET next_permit_at = GREATEST(
			            gmail_quota_gates.next_permit_at,
			            gmail_quota_gates.blocked_until,
			            clock_timestamp()
			        ) + interval '250 milliseconds',
			        updated_at = clock_timestamp()
			    WHERE GREATEST(
			            gmail_quota_gates.next_permit_at,
			            gmail_quota_gates.blocked_until,
			            clock_timestamp()
			        ) <= clock_timestamp() + (CAST(? AS double precision) * interval '1 millisecond')
			    RETURNING next_permit_at
			)
			SELECT GREATEST(
			    0,
			    CEIL(
			        EXTRACT(EPOCH FROM (
			            next_permit_at - interval '250 milliseconds' - clock_timestamp()
			        )) * 1000
			    )
			)::bigint
			FROM reserved
		""".trimIndent(),
		{ resultSet, _ -> resultSet.getLong(1) },
		maxWaitMillis,
		accountKey,
		maxWaitMillis,
		).firstOrNull()
		} catch (exception: DataAccessException) {
			if (exception.isPostgresLockTimeout()) null else throw exception
		}
	}

	private fun DataAccessException.isPostgresLockTimeout(): Boolean =
		generateSequence(this as Throwable?) { it.cause }
			.filterIsInstance<SQLException>()
			.any { it.sqlState == POSTGRES_LOCK_NOT_AVAILABLE }

	internal fun remainingBlockMillis(accountKey: String): Long = jdbcTemplate.queryForObject(
		"""
			SELECT COALESCE(
			    (
			        SELECT GREATEST(
			            0,
			            CEIL(EXTRACT(EPOCH FROM (blocked_until - clock_timestamp())) * 1000)
			        )::bigint
			        FROM gmail_quota_gates
			        WHERE account_key = ?
			    ),
			    0
			)
		""".trimIndent(),
		Long::class.java,
		accountKey,
	) ?: 0L

	private companion object {
		const val MAX_BLOCK_MILLIS = 24 * 60 * 60 * 1_000L
		const val MAX_INLINE_WAIT_MILLIS = 60_000L
		const val POSTGRES_LOCK_NOT_AVAILABLE = "55P03"
	}
}
