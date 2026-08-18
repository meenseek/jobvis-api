package com.meenseek.jobvis.auth

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

@Component
class AuthRetentionWorker(
	private val jdbcTemplate: JdbcTemplate,
	private val clock: Clock,
) {
	@Scheduled(fixedDelayString = "\${jobvis.auth.cleanup-delay:PT1H}")
	@Transactional
	fun purgeExpired(): Int {
		val cutoff = Timestamp.from(Instant.now(clock))
		val loginChallenges = jdbcTemplate.update(
			"DELETE FROM login_challenges WHERE expires_at <= ? OR consumed_at IS NOT NULL",
			cutoff,
		)
		val oauthChallenges = jdbcTemplate.update(
			"""
				DELETE FROM oauth_challenges
				WHERE consumed_at IS NOT NULL
				   OR (
				       expires_at <= ?
				       AND (exchange_claim_expires_at IS NULL OR exchange_claim_expires_at <= ?)
				   )
			""".trimIndent(),
			cutoff, cutoff,
		)
		val sessions = jdbcTemplate.update(
			"DELETE FROM auth_sessions WHERE expires_at <= ? OR revoked_at IS NOT NULL",
			cutoff,
		)
		return loginChallenges + oauthChallenges + sessions
	}
}
