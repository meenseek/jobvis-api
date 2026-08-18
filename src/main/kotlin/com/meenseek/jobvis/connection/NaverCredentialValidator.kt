package com.meenseek.jobvis.connection

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ServiceUnavailableException
import com.meenseek.jobvis.common.TooManyRequestsException
import jakarta.mail.Session
import jakarta.mail.AuthenticationFailedException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Semaphore

fun interface NaverCredentialValidator {
	fun validate(accountEmail: String, appPassword: String)
}

@Component
class AngusNaverCredentialValidator : NaverCredentialValidator {
	override fun validate(accountEmail: String, appPassword: String) {
		val properties = Properties().apply {
			setProperty("mail.store.protocol", "imaps")
			setProperty("mail.imaps.host", "imap.naver.com")
			setProperty("mail.imaps.port", "993")
			setProperty("mail.imaps.ssl.enable", "true")
			setProperty("mail.imaps.connectiontimeout", "10000")
			setProperty("mail.imaps.timeout", "10000")
			setProperty("mail.imaps.writetimeout", "10000")
		}
		val store = Session.getInstance(properties).getStore("imaps")
		try {
			store.connect("imap.naver.com", 993, accountEmail, appPassword)
		} catch (exception: Exception) {
			throw classifyNaverValidationFailure(exception)
		} finally {
			if (store.isConnected) runCatching(store::close)
		}
	}
}

internal fun classifyNaverValidationFailure(exception: Exception): RuntimeException =
	if (exception is AuthenticationFailedException) {
		BadRequestException("네이버 IMAP 인증에 실패했습니다. 2단계 인증과 앱 비밀번호를 확인해 주세요.")
	} else {
		ServiceUnavailableException("네이버 IMAP 서비스에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.")
	}

@Component
class NaverConnectionAttemptGuard(
	private val attemptStore: NaverValidationAttemptStore,
	private val clock: Clock,
	@Value("\${jobvis.connections.naver-validation-window:PT10M}") private val window: Duration,
	@Value("\${jobvis.connections.naver-validation-attempts:5}") private val maxAttempts: Int,
	@Value("\${jobvis.connections.naver-validation-max-clients:20000}") private val maxClients: Int,
	@Value("\${jobvis.connections.naver-validation-max-concurrent:4}") maxConcurrent: Int,
) {
	private val permits = Semaphore(maxConcurrent)

	init {
		require(!window.isZero && !window.isNegative) { "네이버 연결 검증 window는 양수여야 합니다." }
		require(maxAttempts in 1..100) { "네이버 연결 검증 시도 상한은 1~100이어야 합니다." }
		require(maxClients in 1..100_000) { "네이버 연결 검증 추적 상한은 1~100000이어야 합니다." }
		require(maxConcurrent in 1..100) { "네이버 연결 동시 검증 상한은 1~100이어야 합니다." }
	}

	fun <T> execute(userId: UUID, accountEmail: String, action: () -> T): T {
		if (!permits.tryAcquire()) reject()
		return try {
			val now = Instant.now(clock)
			if (!attemptStore.record(userId, accountEmail, now, window, maxAttempts, maxClients)) reject()
			action()
		} finally {
			permits.release()
		}
	}

	private fun reject(): Nothing =
		throw TooManyRequestsException("네이버 연결 확인 요청이 많습니다. 잠시 후 다시 시도해 주세요.")
}

interface NaverValidationAttemptStore {
	fun record(
		userId: UUID,
		accountEmail: String,
		now: Instant,
		window: Duration,
		maxAttempts: Int,
		maxClients: Int,
	): Boolean
}

@Component
class PostgresNaverValidationAttemptStore(
	private val jdbcTemplate: JdbcTemplate,
	private val transactionTemplate: TransactionTemplate,
) : NaverValidationAttemptStore {
	override fun record(
		userId: UUID,
		accountEmail: String,
		now: Instant,
		window: Duration,
		maxAttempts: Int,
		maxClients: Int,
	): Boolean = try {
		transactionTemplate.execute {
			jdbcTemplate.execute("SELECT pg_advisory_xact_lock(742019384)")
			val timestamp = Timestamp.from(now)
			val keys = listOf("user:$userId", "account:${accountEmail.trim().lowercase(Locale.ROOT)}")
			jdbcTemplate.update(
				"DELETE FROM naver_validation_attempts WHERE attempt_key IN (?, ?) AND window_expires_at <= ?",
				keys[0], keys[1], timestamp,
			)
			jdbcTemplate.update(
				"""
					DELETE FROM naver_validation_attempts
					WHERE attempt_key IN (
					    SELECT attempt_key FROM naver_validation_attempts
					    WHERE window_expires_at <= ? ORDER BY window_expires_at LIMIT 100
					)
				""".trimIndent(),
				timestamp,
			)
			val existing = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM naver_validation_attempts WHERE attempt_key IN (?, ?)",
				Long::class.java, keys[0], keys[1],
			) ?: 0
			val tracked = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM naver_validation_attempts", Long::class.java,
			) ?: 0
			if (tracked + (2 - existing) > maxClients.toLong() * 2) throw AttemptLimitReached()
			val expiresAt = Timestamp.from(now.plus(window))
			keys.forEach { key ->
				val count = jdbcTemplate.queryForObject(
					"""
						INSERT INTO naver_validation_attempts (
						    attempt_key, attempt_count, window_expires_at, updated_at
						) VALUES (?, 1, ?, ?)
						ON CONFLICT (attempt_key) DO UPDATE
						SET attempt_count = naver_validation_attempts.attempt_count + 1,
						    updated_at = EXCLUDED.updated_at
						RETURNING attempt_count
					""".trimIndent(),
					Int::class.java, key, expiresAt, timestamp,
				) ?: throw IllegalStateException("네이버 검증 제한 상태를 저장하지 못했습니다.")
				if (count > maxAttempts) throw AttemptLimitReached()
			}
			true
		} == true
	} catch (_: AttemptLimitReached) {
		false
	}

	private class AttemptLimitReached : RuntimeException()
}
