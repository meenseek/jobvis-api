package com.meenseek.jobvis.auth

enum class LoginProvider {
	GOOGLE,
	KAKAO,
	;

	companion object {
		fun fromApiValue(value: String): LoginProvider = entries.firstOrNull {
			it.name.equals(value.trim(), ignoreCase = true)
		} ?: throw com.meenseek.jobvis.common.BadRequestException("지원하지 않는 로그인 공급자입니다.")
	}
}

data class VerifiedIdentity(
	val subject: String,
	val email: String?,
	val emailVerified: Boolean,
	val displayName: String?,
)

interface IdentityTokenVerifier {
	fun isConfigured(provider: LoginProvider): Boolean
	fun verify(provider: LoginProvider, idToken: String, nonce: String): VerifiedIdentity
}
