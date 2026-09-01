package com.meenseek.jobvis.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal fun trustedSiteUserUuid(siteUserId: String): UUID {
	val digest = MessageDigest.getInstance("SHA-256").run {
		update("jobvis:sites:user:v1".toByteArray(StandardCharsets.UTF_8))
		update(0.toByte())
		digest(siteUserId.toByteArray(StandardCharsets.UTF_8))
	}
	val uuidBytes = digest.copyOf(16)
	uuidBytes[6] = ((uuidBytes[6].toInt() and 0x0f) or 0x80).toByte()
	uuidBytes[8] = ((uuidBytes[8].toInt() and 0x3f) or 0x80).toByte()
	val buffer = ByteBuffer.wrap(uuidBytes)
	return UUID(buffer.long, buffer.long)
}

@Component
@Profile("!local")
class SessionCurrentUserProvider(
	private val authSessionRepository: AuthSessionRepository,
	private val userAccountRepository: UserAccountRepository,
	private val clock: Clock,
	@Value("\${jobvis.auth.trusted-site-secret:}")
	private val trustedSiteSecret: String,
) : CurrentUserProvider {
	init {
		require(
			trustedSiteSecret.isBlank() ||
				trustedSiteSecret.toByteArray(StandardCharsets.UTF_8).size >= MINIMUM_SITE_SECRET_BYTES,
		) { "jobvis.auth.trusted-site-secret은 비어 있거나 32바이트 이상이어야 합니다." }
	}

	@Transactional
	override fun currentUserId(request: HttpServletRequest): UUID {
		trustedSiteUserId(request)?.let { userId ->
			userAccountRepository.provisionUser(userId, Instant.now(clock))
			return userId
		}

		val tokenHash = TokenDigests.sha256Hex(BearerTokens.from(request))
		val now = Instant.now(clock)
		val session = authSessionRepository.findActive(tokenHash, now)
			?: throw com.meenseek.jobvis.common.UnauthorizedException("로그인이 필요합니다.")
		authSessionRepository.touchActive(tokenHash, now, now.minusSeconds(60))
		return session.userId
	}

	private fun trustedSiteUserId(request: HttpServletRequest): UUID? {
		val siteUserId = request.getHeader(SITE_USER_ID_HEADER)
		val presentedSecret = request.getHeader(SITE_SECRET_HEADER)
		if (siteUserId == null && presentedSecret == null) return null

		if (
			trustedSiteSecret.isBlank() ||
			siteUserId.isNullOrBlank() ||
			siteUserId.length > MAXIMUM_SITE_USER_ID_LENGTH ||
			presentedSecret == null ||
			!MessageDigest.isEqual(
				trustedSiteSecret.toByteArray(StandardCharsets.UTF_8),
				presentedSecret.toByteArray(StandardCharsets.UTF_8),
			)
		) {
			throw com.meenseek.jobvis.common.UnauthorizedException("로그인이 필요합니다.")
		}

		return trustedSiteUserUuid(siteUserId)
	}

	companion object {
		const val SITE_USER_ID_HEADER = "X-Jobvis-Site-User-Id"
		const val SITE_SECRET_HEADER = "X-Jobvis-Site-Gateway-Secret"
		private const val MINIMUM_SITE_SECRET_BYTES = 32
		private const val MAXIMUM_SITE_USER_ID_LENGTH = 512
	}
}
