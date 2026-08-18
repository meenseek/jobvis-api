package com.meenseek.jobvis.auth

import com.meenseek.jobvis.common.ServiceUnavailableException
import com.meenseek.jobvis.common.UnauthorizedException
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.client.RestOperations
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Component
class OidcIdentityTokenVerifier(
	@Value("\${jobvis.auth.google-client-id:}")
	private val googleClientId: String,
	@Value("\${jobvis.auth.kakao-client-id:}")
	private val kakaoClientId: String,
	@Qualifier("externalRestOperations") private val externalRestOperations: RestOperations,
) : IdentityTokenVerifier {
	private val decoders = ConcurrentHashMap<LoginProvider, NimbusJwtDecoder>()

	override fun isConfigured(provider: LoginProvider): Boolean = clientId(provider).isNotBlank()

	override fun verify(provider: LoginProvider, idToken: String, nonce: String): VerifiedIdentity {
		if (!isConfigured(provider)) {
			throw ServiceUnavailableException("${provider.name.lowercase()} 로그인이 아직 설정되지 않았습니다.")
		}
		val jwt = try {
			decoders.computeIfAbsent(provider, ::createDecoder).decode(idToken)
		} catch (_: JwtException) {
			throw UnauthorizedException("로그인 토큰을 검증할 수 없습니다.")
		}
		if (!constantTimeEquals(nonce, jwt.getClaimAsString("nonce"))) {
			throw UnauthorizedException("로그인 nonce가 일치하지 않습니다.")
		}
		val subject = jwt.subject?.takeIf(String::isNotBlank)
			?: throw UnauthorizedException("로그인 토큰에 사용자 식별자가 없습니다.")
		return VerifiedIdentity(
			subject = subject,
			email = jwt.getClaimAsString("email")?.takeIf(String::isNotBlank),
			emailVerified = jwt.claims["email_verified"] as? Boolean ?: false,
			displayName = jwt.getClaimAsString("name")?.takeIf(String::isNotBlank)
				?: jwt.getClaimAsString("nickname")?.takeIf(String::isNotBlank),
		)
	}

	private fun createDecoder(provider: LoginProvider): NimbusJwtDecoder {
		val configuration = configuration(provider)
		return NimbusJwtDecoder.withJwkSetUri(configuration.jwkSetUri)
			.restOperations(externalRestOperations)
			.build().also { decoder ->
			decoder.setJwtValidator(
				org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator(
					JwtTimestampValidator(),
					IssuerAudienceValidator(configuration.issuers, configuration.audience),
				),
			)
		}
	}

	private fun clientId(provider: LoginProvider): String = when (provider) {
		LoginProvider.GOOGLE -> googleClientId
		LoginProvider.KAKAO -> kakaoClientId
	}

	private fun configuration(provider: LoginProvider): ProviderConfiguration = when (provider) {
		LoginProvider.GOOGLE -> ProviderConfiguration(
			jwkSetUri = "https://www.googleapis.com/oauth2/v3/certs",
			issuers = setOf("https://accounts.google.com", "accounts.google.com"),
			audience = googleClientId,
		)
		LoginProvider.KAKAO -> ProviderConfiguration(
			jwkSetUri = "https://kauth.kakao.com/.well-known/jwks.json",
			issuers = setOf("https://kauth.kakao.com"),
			audience = kakaoClientId,
		)
	}

	private fun constantTimeEquals(expected: String, actual: String?): Boolean = actual != null &&
		MessageDigest.isEqual(
			expected.toByteArray(StandardCharsets.UTF_8),
			actual.toByteArray(StandardCharsets.UTF_8),
		)

	private data class ProviderConfiguration(
		val jwkSetUri: String,
		val issuers: Set<String>,
		val audience: String,
	)

	private class IssuerAudienceValidator(
		private val issuers: Set<String>,
		private val audience: String,
	) : OAuth2TokenValidator<Jwt> {
		override fun validate(token: Jwt): OAuth2TokenValidatorResult {
			if (token.issuer?.toString() !in issuers || audience !in token.audience.orEmpty()) {
				return OAuth2TokenValidatorResult.failure(
					OAuth2Error("invalid_token", "issuer 또는 audience가 올바르지 않습니다.", null),
				)
			}
			return OAuth2TokenValidatorResult.success()
		}
	}
}
