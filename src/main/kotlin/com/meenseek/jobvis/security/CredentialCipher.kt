package com.meenseek.jobvis.security

import com.meenseek.jobvis.common.ServiceUnavailableException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface CredentialCipher {
	val available: Boolean
	fun encrypt(plaintext: String, context: String): String
	fun decrypt(ciphertext: String, context: String): String
}

@Component
class AesGcmCredentialCipher(
	@Value("\${jobvis.crypto.key-base64:}") encodedKey: String,
) : CredentialCipher {
	private val secureRandom = SecureRandom()
	private val key: SecretKeySpec? = encodedKey.takeIf(String::isNotBlank)?.let { value ->
		val decoded = runCatching { Base64.getDecoder().decode(value) }
			.getOrElse { throw IllegalStateException("JOBVIS_ENCRYPTION_KEY_BASE64는 올바른 Base64여야 합니다.") }
		check(decoded.size == 32) { "JOBVIS_ENCRYPTION_KEY_BASE64는 32바이트 키여야 합니다." }
		SecretKeySpec(decoded, "AES")
	}

	override val available: Boolean get() = key != null

	override fun encrypt(plaintext: String, context: String): String {
		val activeKey = requireKey()
		val nonce = ByteArray(NONCE_SIZE).also(secureRandom::nextBytes)
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, activeKey, GCMParameterSpec(TAG_BITS, nonce))
		cipher.updateAAD(context.toByteArray(StandardCharsets.UTF_8))
		val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
		return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + encrypted)
	}

	override fun decrypt(ciphertext: String, context: String): String {
		val activeKey = requireKey()
		if (!ciphertext.startsWith("v1:")) throw IllegalStateException("지원하지 않는 암호문 버전입니다.")
		val payload = runCatching { Base64.getUrlDecoder().decode(ciphertext.removePrefix("v1:")) }
			.getOrElse { throw IllegalStateException("자격증명 암호문을 해석할 수 없습니다.") }
		if (payload.size <= NONCE_SIZE) throw IllegalStateException("자격증명 암호문이 손상되었습니다.")
		val nonce = payload.copyOfRange(0, NONCE_SIZE)
		val encrypted = payload.copyOfRange(NONCE_SIZE, payload.size)
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.DECRYPT_MODE, activeKey, GCMParameterSpec(TAG_BITS, nonce))
		cipher.updateAAD(context.toByteArray(StandardCharsets.UTF_8))
		return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
	}

	private fun requireKey(): SecretKeySpec = key
		?: throw ServiceUnavailableException("자격증명 암호화 키가 설정되지 않았습니다.")

	private companion object {
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val NONCE_SIZE = 12
		const val TAG_BITS = 128
	}
}
