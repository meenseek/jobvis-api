package com.meenseek.jobvis.auth

import com.meenseek.jobvis.common.UnauthorizedException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Component
@Profile("local")
class LocalCurrentUserProvider(
	private val userAccountRepository: UserAccountRepository,
	private val clock: Clock,
) : CurrentUserProvider {

	@Transactional
	override fun currentUserId(request: HttpServletRequest): UUID {
		if (!request.remoteAddr.isLoopbackAddress()) {
			throw UnauthorizedException("로컬 사용자 헤더는 이 컴퓨터에서만 사용할 수 있습니다.")
		}

		val userId = request.getHeader(USER_ID_HEADER).toUserId()
		userAccountRepository.provisionLocalUser(userId, Instant.now(clock))
		return userId
	}

	private fun String?.toUserId(): UUID {
		if (isNullOrBlank()) {
			throw UnauthorizedException("로컬 사용자 헤더가 필요합니다.")
		}
		return runCatching { UUID.fromString(this) }
			.getOrElse { throw UnauthorizedException("로컬 사용자 헤더가 올바른 UUID가 아닙니다.") }
	}

	private fun String?.isLoopbackAddress(): Boolean =
		this != null && runCatching { InetAddress.getByName(this).isLoopbackAddress }.getOrDefault(false)

	companion object {
		const val USER_ID_HEADER = "X-Jobvis-User-Id"
	}
}
