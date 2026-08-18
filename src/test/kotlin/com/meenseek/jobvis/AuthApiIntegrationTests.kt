package com.meenseek.jobvis

import com.meenseek.jobvis.auth.IdentityTokenVerifier
import com.meenseek.jobvis.auth.LoginProvider
import com.meenseek.jobvis.auth.VerifiedIdentity
import com.meenseek.jobvis.auth.AuthSessionRepository
import com.meenseek.jobvis.auth.TokenDigests
import com.meenseek.jobvis.common.UnauthorizedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthApiIntegrationTests.IdentityVerifierConfiguration::class)
class AuthApiIntegrationTests @Autowired constructor(
	private val mockMvc: MockMvc,
	private val objectMapper: ObjectMapper,
	private val verifierTransactionObservation: VerifierTransactionObservation,
	private val authSessionRepository: AuthSessionRepository,
	private val transactionTemplate: TransactionTemplate,
) : PostgresIntegrationTest() {
	@Test
	fun `검증된 외부 ID 토큰은 서버 세션으로 교환되고 로그아웃하면 즉시 폐기된다`() {
		mockMvc.perform(get("/api/v1/auth/providers"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$[0].configured").value(true))
		val challenge = createChallenge()

		val response = mockMvc.perform(
			post("/api/v1/auth/exchange")
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"provider" to "google",
							"idToken" to "valid-token",
							"challengeToken" to challenge.path("challengeToken").asString(),
							"nonce" to challenge.path("nonce").asString(),
						),
					),
				),
		).andExpect(status().isOk)
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.user.primaryEmail").value("user@example.com"))
			.andReturn().response.contentAsString

		val body = objectMapper.readTree(response)
		assertThat(verifierTransactionObservation.active.get()).isFalse()
		val accessToken = body.path("accessToken").asString()
		val userId = body.path("user").path("id").asString()
		assertThat(accessToken).doesNotContain(userId)

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer $accessToken"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(userId))

		mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer $accessToken"))
			.andExpect(status().isNoContent)
		val now = Instant.now()
		val touched = transactionTemplate.execute {
			authSessionRepository.touchActive(
				TokenDigests.sha256Hex(accessToken), now, now.plusSeconds(1),
			)
		}
		assertThat(touched).isZero()

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer $accessToken"))
			.andExpect(status().isUnauthorized)

		mockMvc.perform(
			post("/api/v1/auth/exchange")
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"provider" to "google",
							"idToken" to "valid-token",
							"challengeToken" to challenge.path("challengeToken").asString(),
							"nonce" to challenge.path("nonce").asString(),
						),
					),
				),
		).andExpect(status().isUnauthorized)
	}

	@Test
	fun `운영 인증은 로컬 사용자 헤더를 신뢰하지 않고 검증 실패를 401로 반환한다`() {
		mockMvc.perform(get("/api/v1/auth/me").header("X-Jobvis-User-Id", java.util.UUID.randomUUID()))
			.andExpect(status().isUnauthorized)

		val challenge = createChallenge()
		mockMvc.perform(
			post("/api/v1/auth/exchange")
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"provider" to "google",
							"idToken" to "invalid",
							"challengeToken" to challenge.path("challengeToken").asString(),
							"nonce" to challenge.path("nonce").asString(),
						),
					),
				),
		).andExpect(status().isUnauthorized)
	}

	@Test
	fun `존재하지 않는 챌린지는 외부 토큰 검증을 호출하지 않는다`() {
		verifierTransactionObservation.calls.set(0)
		mockMvc.perform(
			post("/api/v1/auth/exchange")
				.contentType(MediaType.APPLICATION_JSON)
				.content(
					objectMapper.writeValueAsString(
						mapOf(
							"provider" to "google",
							"idToken" to "valid-token",
							"challengeToken" to "missing-challenge",
							"nonce" to "missing-nonce",
						),
					),
			),
		).andExpect(status().isUnauthorized)
		assertThat(verifierTransactionObservation.calls.get()).isZero()
	}

	private fun createChallenge() = objectMapper.readTree(
		mockMvc.perform(
			post("/api/v1/auth/challenges")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(mapOf("provider" to "google"))),
		).andExpect(status().isCreated)
			.andReturn().response.contentAsString,
	)

	@TestConfiguration
	class IdentityVerifierConfiguration {
		@Bean
		fun verifierTransactionObservation(): VerifierTransactionObservation = VerifierTransactionObservation()

		@Bean
		@Primary
		fun identityTokenVerifier(observation: VerifierTransactionObservation): IdentityTokenVerifier =
			object : IdentityTokenVerifier {
			override fun isConfigured(provider: LoginProvider): Boolean = true

				override fun verify(provider: LoginProvider, idToken: String, nonce: String): VerifiedIdentity {
					observation.calls.incrementAndGet()
				observation.active.set(TransactionSynchronizationManager.isActualTransactionActive())
				if (provider != LoginProvider.GOOGLE || idToken != "valid-token" || nonce.isBlank()) {
					throw UnauthorizedException("로그인 토큰을 검증할 수 없습니다.")
				}
				return VerifiedIdentity(
					subject = "google-user-1",
					email = "user@example.com",
					emailVerified = true,
					displayName = "테스트 사용자",
				)
			}
		}
	}

	class VerifierTransactionObservation {
		val active: AtomicReference<Boolean?> = AtomicReference()
		val calls: AtomicInteger = AtomicInteger()
	}
}
