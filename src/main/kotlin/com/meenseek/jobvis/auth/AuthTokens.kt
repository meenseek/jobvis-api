package com.meenseek.jobvis.auth

import com.meenseek.jobvis.common.UnauthorizedException
import jakarta.servlet.http.HttpServletRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object BearerTokens {
	fun from(request: HttpServletRequest): String {
		val authorization = request.getHeader("Authorization")?.trim().orEmpty()
		if (!authorization.startsWith(PREFIX, ignoreCase = true)) {
			throw UnauthorizedException("로그인이 필요합니다.")
		}
		return authorization.substring(PREFIX.length).trim()
			.takeIf(String::isNotEmpty)
			?: throw UnauthorizedException("로그인이 필요합니다.")
	}

	private const val PREFIX = "Bearer "
}

object TokenDigests {
	fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(StandardCharsets.UTF_8))
		.joinToString("") { byte -> "%02x".format(byte) }
}

object SessionTokens {
	private val secureRandom = SecureRandom()

	fun generate(): String {
		val bytes = ByteArray(32)
		secureRandom.nextBytes(bytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}
}
