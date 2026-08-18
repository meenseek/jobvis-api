package com.meenseek.jobvis.connection

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class OAuthRefreshClaimService(
	private val jdbcTemplate: JdbcTemplate,
	private val transactionTemplate: TransactionTemplate,
	@Value("\${jobvis.connections.oauth-refresh-lease:PT1M}") private val refreshLease: Duration,
	@Value("\${jobvis.external-http.connect-timeout:PT5S}") externalConnectTimeout: Duration,
	@Value("\${jobvis.external-http.read-timeout:PT30S}") externalReadTimeout: Duration,
) {
	init {
		val minimumLease = externalConnectTimeout.plus(externalReadTimeout).plusSeconds(10)
		require(refreshLease > minimumLease) {
			"OAuth refresh lease는 외부 token 호출의 최대 시간보다 10초 이상 길어야 합니다."
		}
	}

	fun tryClaim(userId: UUID, connectionId: UUID, connectionVersion: Long, now: Instant): UUID? =
		transactionTemplate.execute {
			val claimToken = UUID.randomUUID()
			jdbcTemplate.query(
				"""
					INSERT INTO connection_refresh_claims (
					    connection_id, user_id, connection_version, claim_token, expires_at, created_at
					)
					SELECT connection.id, connection.user_id, connection.version, ?, ?, ?
					FROM external_connections connection
					WHERE connection.id = ? AND connection.user_id = ?
					  AND connection.version = ? AND connection.status = 'CONNECTED'
					ON CONFLICT (connection_id) DO UPDATE
					SET user_id = EXCLUDED.user_id,
					    connection_version = EXCLUDED.connection_version,
					    claim_token = EXCLUDED.claim_token,
					    expires_at = EXCLUDED.expires_at,
					    created_at = EXCLUDED.created_at
					WHERE connection_refresh_claims.connection_version <> EXCLUDED.connection_version
					   OR connection_refresh_claims.expires_at <= ?
					RETURNING claim_token
				""".trimIndent(),
				{ resultSet, _ -> resultSet.getObject("claim_token", UUID::class.java) },
				claimToken,
				Timestamp.from(now.plus(refreshLease)),
				Timestamp.from(now),
				connectionId,
				userId,
				connectionVersion,
				Timestamp.from(now),
			).firstOrNull()
		}

	fun consumeOwned(
		connectionId: UUID,
		connectionVersion: Long,
		claimToken: UUID,
		now: Instant,
	): Boolean = jdbcTemplate.query(
		"""
			DELETE FROM connection_refresh_claims
			WHERE connection_id = ? AND connection_version = ? AND claim_token = ?
			  AND expires_at > ?
			RETURNING connection_id
		""".trimIndent(),
		{ resultSet, _ -> resultSet.getObject("connection_id", UUID::class.java) },
		connectionId,
		connectionVersion,
		claimToken,
		Timestamp.from(now),
	).isNotEmpty()

	fun release(connectionId: UUID, claimToken: UUID) {
		jdbcTemplate.update(
			"DELETE FROM connection_refresh_claims WHERE connection_id = ? AND claim_token = ?",
			connectionId,
			claimToken,
		)
	}
}
