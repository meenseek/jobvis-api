package com.meenseek.jobvis.application

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

object RequestFingerprint {
	fun of(vararg values: Any): String {
		val canonical = buildString {
			values.forEach { value ->
				val text = value.toString()
				append(text.length).append(':').append(text).append('|')
			}
		}
		val digest = MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(StandardCharsets.UTF_8))
		return HexFormat.of().formatHex(digest)
	}
}
