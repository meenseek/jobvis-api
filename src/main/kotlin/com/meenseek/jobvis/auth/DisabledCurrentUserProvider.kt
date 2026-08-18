package com.meenseek.jobvis.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Component
@Profile("!local")
class SessionCurrentUserProvider(
	private val authSessionRepository: AuthSessionRepository,
	private val clock: Clock,
) : CurrentUserProvider {

	@Transactional
	override fun currentUserId(request: HttpServletRequest): UUID {
		val tokenHash = TokenDigests.sha256Hex(BearerTokens.from(request))
		val now = Instant.now(clock)
		val session = authSessionRepository.findActive(tokenHash, now)
			?: throw com.meenseek.jobvis.common.UnauthorizedException("로그인이 필요합니다.")
		authSessionRepository.touchActive(tokenHash, now, now.minusSeconds(60))
		return session.userId
	}
}
