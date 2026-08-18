package com.meenseek.jobvis.connection

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ServiceUnavailableException
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant

data class OAuthConnectionTokens(
	val accessToken: String,
	val refreshToken: String?,
	val expiresAt: Instant?,
	val scopes: Set<String>,
	val accountEmail: String,
)

data class OAuthRefreshedTokens(
	val accessToken: String,
	val refreshToken: String?,
	val expiresAt: Instant?,
	val scopes: Set<String>,
)

enum class OAuthRefreshFailureDisposition { REAUTHORIZATION_REQUIRED, TRANSIENT }

class OAuthRefreshException(
	val disposition: OAuthRefreshFailureDisposition,
	cause: Throwable? = null,
) : RuntimeException("OAuth refresh failed", cause)

class OAuthCodeConsumedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface OAuthConnectionClient {
	fun isConfigured(provider: ConnectionProvider): Boolean
	fun authorizationUrl(
		provider: ConnectionProvider,
		redirectUri: String,
		state: String,
		codeChallenge: String,
	): String
	fun exchange(
		provider: ConnectionProvider,
		redirectUri: String,
		code: String,
		codeVerifier: String,
	): OAuthConnectionTokens

	fun refresh(provider: ConnectionProvider, refreshToken: String): OAuthRefreshedTokens =
		throw ServiceUnavailableException("OAuth 토큰 갱신을 지원하지 않습니다.")
}

@Component
class OfficialOAuthConnectionClient(
	private val objectMapper: ObjectMapper,
	private val clock: Clock,
	@Qualifier("externalRestClient") private val restClient: RestClient,
	@Value("\${jobvis.connections.gmail-client-id:}") private val gmailClientId: String,
	@Value("\${jobvis.connections.gmail-client-secret:}") private val gmailClientSecret: String,
	@Value("\${jobvis.connections.google-calendar-client-id:}") private val calendarClientId: String,
	@Value("\${jobvis.connections.google-calendar-client-secret:}") private val calendarClientSecret: String,
	@Value("\${jobvis.connections.microsoft-client-id:}") private val microsoftClientId: String,
	@Value("\${jobvis.connections.microsoft-client-secret:}") private val microsoftClientSecret: String,
) : OAuthConnectionClient {

	override fun isConfigured(provider: ConnectionProvider): Boolean = runCatching { configuration(provider) }
		.getOrNull()?.let { it.clientId.isNotBlank() && it.clientSecret.isNotBlank() } ?: false

	override fun authorizationUrl(
		provider: ConnectionProvider,
		redirectUri: String,
		state: String,
		codeChallenge: String,
	): String {
		val configuration = configured(provider)
		val builder = UriComponentsBuilder.fromUriString(configuration.authorizationEndpoint)
			.queryParam("client_id", configuration.clientId)
			.queryParam("redirect_uri", redirectUri)
			.queryParam("response_type", "code")
			.queryParam("scope", configuration.scopes.joinToString(" "))
			.queryParam("state", state)
			.queryParam("code_challenge", codeChallenge)
			.queryParam("code_challenge_method", "S256")
		if (configuration.authority == OAuthAuthority.GOOGLE) {
			builder.queryParam("access_type", "offline").queryParam("prompt", "consent")
		}
		return builder.build().encode().toUriString()
	}

	override fun exchange(
		provider: ConnectionProvider,
		redirectUri: String,
		code: String,
		codeVerifier: String,
	): OAuthConnectionTokens {
		val configuration = configured(provider)
		val form = LinkedMultiValueMap<String, String>().apply {
			add("client_id", configuration.clientId)
			add("client_secret", configuration.clientSecret)
			add("redirect_uri", redirectUri)
			add("grant_type", "authorization_code")
			add("code", code)
			add("code_verifier", codeVerifier)
		}
		val parsed = requestTokens(configuration, form, "외부 서비스의 OAuth 코드를 교환할 수 없습니다.")
		val accountEmail = try {
			fetchAccountEmail(configuration.authority, parsed.accessToken)
		} catch (exception: Exception) {
			throw OAuthCodeConsumedException(
				"OAuth 코드는 사용되었지만 외부 계정 정보를 확인하지 못했습니다. 연결을 다시 시작해 주세요.",
				exception,
			)
		}
		return OAuthConnectionTokens(
			accessToken = parsed.accessToken,
			refreshToken = parsed.refreshToken,
			expiresAt = parsed.expiresAt,
			scopes = parsed.scopes,
			accountEmail = accountEmail,
		)
	}

	override fun refresh(provider: ConnectionProvider, refreshToken: String): OAuthRefreshedTokens {
		val configuration = configured(provider)
		val form = LinkedMultiValueMap<String, String>().apply {
			add("client_id", configuration.clientId)
			add("client_secret", configuration.clientSecret)
			add("grant_type", "refresh_token")
			add("refresh_token", refreshToken)
		}
		val parsed = requestTokens(
			configuration, form, "외부 서비스의 OAuth 토큰을 갱신할 수 없습니다.", refreshing = true,
		)
		return OAuthRefreshedTokens(
			parsed.accessToken,
			parsed.refreshToken,
			parsed.expiresAt,
			parsed.scopes,
		)
	}

	private fun requestTokens(
		configuration: ProviderConfiguration,
		form: LinkedMultiValueMap<String, String>,
		errorMessage: String,
		refreshing: Boolean = false,
	): ParsedTokens {
		val tokenJson = try {
			restClient.post()
				.uri(configuration.tokenEndpoint)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(String::class.java)
				?.let(objectMapper::readTree)
				?: throw BadRequestException("OAuth 토큰 응답이 비어 있습니다.")
		} catch (exception: BadRequestException) {
			throw exception
			} catch (exception: RestClientResponseException) {
				if (refreshing) throw classifyRefreshFailure(exception)
				if (exception.statusCode.value() == 429 || exception.statusCode.is5xxServerError) {
					throw ServiceUnavailableException("외부 OAuth 서비스가 일시적으로 응답하지 않습니다.")
				}
				throw BadRequestException(errorMessage)
			} catch (exception: RestClientException) {
				if (refreshing) throw OAuthRefreshException(OAuthRefreshFailureDisposition.TRANSIENT, exception)
				throw ServiceUnavailableException("외부 OAuth 서비스가 일시적으로 응답하지 않습니다.")
		}
		val accessToken = tokenJson.path("access_token").asString().takeIf(String::isNotBlank)
			?: throw BadRequestException("OAuth 응답에 액세스 토큰이 없습니다.")
		val nextRefreshToken = tokenJson.path("refresh_token").asString().takeIf(String::isNotBlank)
		val expiresIn = tokenJson.path("expires_in").asLong(0).takeIf { it > 0 }
		val scopes = tokenJson.path("scope").asString()
			.split(' ')
			.filter(String::isNotBlank)
			.toSet()
		return ParsedTokens(
			accessToken,
			nextRefreshToken,
			expiresIn?.let { Instant.now(clock).plusSeconds(it) },
			scopes,
		)
	}

	private fun classifyRefreshFailure(exception: RestClientResponseException): OAuthRefreshException {
		val oauthError = runCatching {
			objectMapper.readTree(exception.responseBodyAsString).path("error").asString()
		}.getOrDefault("")
		val disposition = when {
			oauthError == "invalid_grant" || exception.statusCode.value() == 401 ->
				OAuthRefreshFailureDisposition.REAUTHORIZATION_REQUIRED
			else -> OAuthRefreshFailureDisposition.TRANSIENT
		}
		return OAuthRefreshException(disposition, exception)
	}

	private fun fetchAccountEmail(authority: OAuthAuthority, accessToken: String): String {
		val uri = when (authority) {
			OAuthAuthority.GOOGLE -> "https://openidconnect.googleapis.com/v1/userinfo"
			OAuthAuthority.MICROSOFT -> "https://graph.microsoft.com/v1.0/me?\$select=mail,userPrincipalName"
		}
		val json = try {
			restClient.get()
				.uri(uri)
				.header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
				.retrieve()
				.body(String::class.java)
				?.let(objectMapper::readTree)
				?: throw BadRequestException("외부 계정 정보 응답이 비어 있습니다.")
		} catch (exception: BadRequestException) {
			throw exception
		} catch (_: RestClientException) {
			throw BadRequestException("외부 계정 정보를 확인할 수 없습니다.")
		}
		val email = when (authority) {
			OAuthAuthority.GOOGLE -> json.path("email").asString()
			OAuthAuthority.MICROSOFT -> json.path("mail").asString().ifBlank {
				json.path("userPrincipalName").asString()
			}
		}.trim().lowercase()
		return email.takeIf { it.contains('@') }
			?: throw BadRequestException("외부 계정의 이메일 주소를 확인할 수 없습니다.")
	}

	private fun configured(provider: ConnectionProvider): ProviderConfiguration = configuration(provider).also {
		if (it.clientId.isBlank() || it.clientSecret.isBlank()) {
			throw ServiceUnavailableException("${provider.apiValue()} OAuth가 아직 설정되지 않았습니다.")
		}
	}

	private fun configuration(provider: ConnectionProvider): ProviderConfiguration = when (provider) {
		ConnectionProvider.GMAIL -> ProviderConfiguration(
			OAuthAuthority.GOOGLE,
			gmailClientId,
			gmailClientSecret,
			"https://accounts.google.com/o/oauth2/v2/auth",
			"https://oauth2.googleapis.com/token",
			setOf("openid", "email", "https://www.googleapis.com/auth/gmail.readonly"),
		)
		ConnectionProvider.GOOGLE_CALENDAR -> ProviderConfiguration(
			OAuthAuthority.GOOGLE,
			calendarClientId,
			calendarClientSecret,
			"https://accounts.google.com/o/oauth2/v2/auth",
			"https://oauth2.googleapis.com/token",
			setOf("openid", "email", "https://www.googleapis.com/auth/calendar.events"),
		)
		ConnectionProvider.OUTLOOK -> ProviderConfiguration(
			OAuthAuthority.MICROSOFT,
			microsoftClientId,
			microsoftClientSecret,
			"https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
			"https://login.microsoftonline.com/common/oauth2/v2.0/token",
			setOf("offline_access", "User.Read", "Mail.Read"),
		)
		ConnectionProvider.NAVER -> throw BadRequestException("네이버 메일은 OAuth 연결을 지원하지 않습니다.")
	}

	private data class ProviderConfiguration(
		val authority: OAuthAuthority,
		val clientId: String,
		val clientSecret: String,
		val authorizationEndpoint: String,
		val tokenEndpoint: String,
		val scopes: Set<String>,
	)

	private data class ParsedTokens(
		val accessToken: String,
		val refreshToken: String?,
		val expiresAt: Instant?,
		val scopes: Set<String>,
	)
}
