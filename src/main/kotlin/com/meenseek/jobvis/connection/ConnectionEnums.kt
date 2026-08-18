package com.meenseek.jobvis.connection

import com.meenseek.jobvis.common.BadRequestException
import java.util.Locale

enum class ConnectionProvider(
	val capability: ConnectionCapability,
	val credentialKind: CredentialKind,
) {
	GMAIL(ConnectionCapability.MAIL, CredentialKind.OAUTH2),
	OUTLOOK(ConnectionCapability.MAIL, CredentialKind.OAUTH2),
	NAVER(ConnectionCapability.MAIL, CredentialKind.APP_PASSWORD),
	GOOGLE_CALENDAR(ConnectionCapability.CALENDAR, CredentialKind.OAUTH2),
	;

	fun apiValue(): String = name.lowercase(Locale.ROOT)

	fun requiredScopes(): Set<String> = when (this) {
		GMAIL -> setOf("https://www.googleapis.com/auth/gmail.readonly")
		OUTLOOK -> setOf("Mail.Read")
		NAVER -> setOf("imap.readonly")
		GOOGLE_CALENDAR -> setOf("https://www.googleapis.com/auth/calendar.events")
	}

	fun hasRequiredScopes(grantedScopes: Set<String>): Boolean {
		val normalized = grantedScopes.map { it.trim().lowercase(Locale.ROOT) }.toSet()
		return requiredScopes().all { it.lowercase(Locale.ROOT) in normalized }
	}

	companion object {
		fun fromApiValue(value: String): ConnectionProvider = entries.firstOrNull {
			it.name.equals(value.trim(), ignoreCase = true)
		} ?: throw BadRequestException("지원하지 않는 외부 연결 공급자입니다.")
	}
}

enum class ConnectionCapability { MAIL, CALENDAR }
enum class CredentialKind { OAUTH2, APP_PASSWORD }
enum class ConnectionStatus { CONNECTED, REAUTHORIZATION_REQUIRED, ERROR, REVOKED }
