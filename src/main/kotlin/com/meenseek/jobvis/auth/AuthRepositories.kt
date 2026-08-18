package com.meenseek.jobvis.auth

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AuthIdentityRepository : JpaRepository<AuthIdentity, UUID> {
	@Query(
		"SELECT identity FROM AuthIdentity identity " +
			"WHERE identity.storedProvider = :provider AND identity.storedSubject = :subject",
	)
	fun findByProviderAndSubject(
		@Param("provider") provider: LoginProvider,
		@Param("subject") subject: String,
	): AuthIdentity?
}

interface AuthSessionRepository : JpaRepository<AuthSession, UUID> {
	@Query(
		"SELECT session FROM AuthSession session " +
			"WHERE session.storedTokenHash = :tokenHash " +
			"AND session.storedRevokedAt IS NULL AND session.storedExpiresAt > :now",
	)
	fun findActive(@Param("tokenHash") tokenHash: String, @Param("now") now: Instant): AuthSession?

	@Modifying
	@Query(
		value = """
			UPDATE auth_sessions
			SET last_seen_at = :now
			WHERE token_hash = :tokenHash
			  AND revoked_at IS NULL AND expires_at > :now
			  AND last_seen_at < :touchBefore
		""",
		nativeQuery = true,
	)
	fun touchActive(
		@Param("tokenHash") tokenHash: String,
		@Param("now") now: Instant,
		@Param("touchBefore") touchBefore: Instant,
	): Int

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT session FROM AuthSession session WHERE session.storedTokenHash = :tokenHash")
	fun findLockedByTokenHash(@Param("tokenHash") tokenHash: String): AuthSession?

	@Query("SELECT count(session) > 0 FROM AuthSession session WHERE session.storedTokenHash = :tokenHash")
	fun existsByTokenHash(@Param("tokenHash") tokenHash: String): Boolean
}
