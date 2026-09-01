package com.meenseek.jobvis.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Service
class ApplicationReviewStateService(
	private val jdbcTemplate: JdbcTemplate,
) {
	fun lock(userId: UUID, now: Instant): Long {
		jdbcTemplate.update(
			"INSERT INTO application_review_states (user_id, review_revision, updated_at) " +
				"VALUES (?, 0, ?) ON CONFLICT (user_id) DO NOTHING",
			userId, Timestamp.from(now),
		)
		return jdbcTemplate.queryForObject(
			"SELECT review_revision FROM application_review_states WHERE user_id = ? FOR UPDATE",
			Long::class.java,
			userId,
		) ?: 0
	}

	fun increment(userId: UUID, now: Instant): Long = jdbcTemplate.queryForObject(
		"""
			UPDATE application_review_states
			SET review_revision = review_revision + 1, updated_at = ?
			WHERE user_id = ?
			RETURNING review_revision
		""".trimIndent(),
		Long::class.java,
		Timestamp.from(now), userId,
	) ?: throw IllegalStateException("검토 revision을 갱신할 수 없습니다.")
}
