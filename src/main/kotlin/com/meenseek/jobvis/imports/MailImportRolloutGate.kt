package com.meenseek.jobvis.imports

import com.meenseek.jobvis.common.ServiceUnavailableException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

internal const val MAIL_FINALIZATION_ROLLOUT_LOCK_SQL =
	"SELECT pg_advisory_xact_lock(hashtextextended('jobvis:mail-finalization-rollout', 0))"

internal fun JdbcTemplate.lockMailFinalizationRollout() {
	queryForList(MAIL_FINALIZATION_ROLLOUT_LOCK_SQL)
}

@Component
class MailImportRolloutGate(
	private val jdbcTemplate: JdbcTemplate,
) {
	fun ready(): Boolean = jdbcTemplate.queryForObject(
		"SELECT completed_at IS NOT NULL FROM mail_finalization_rollout_state WHERE singleton = true",
		Boolean::class.java,
	) == true

	fun requireReady() {
		if (!ready()) {
			throw ServiceUnavailableException("기존 메일 가져오기 데이터를 정리한 뒤 동기화를 사용할 수 있습니다.")
		}
	}
}
