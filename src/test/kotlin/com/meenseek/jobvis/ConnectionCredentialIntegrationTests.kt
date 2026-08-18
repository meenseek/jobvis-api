package com.meenseek.jobvis

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.common.ExternalConnectionAuthorizationException
import com.meenseek.jobvis.common.ServiceUnavailableException
import com.meenseek.jobvis.connection.ConnectionCredentialService
import com.meenseek.jobvis.connection.ConnectionProvider
import com.meenseek.jobvis.connection.ConnectionService
import com.meenseek.jobvis.connection.OAuthConnectionClient
import com.meenseek.jobvis.connection.OAuthConnectionTokens
import com.meenseek.jobvis.connection.OAuthRefreshedTokens
import com.meenseek.jobvis.connection.OAuthRefreshException
import com.meenseek.jobvis.connection.OAuthRefreshFailureDisposition
import com.meenseek.jobvis.connection.OAuthRefreshClaimService
import com.meenseek.jobvis.connection.ConnectionStateService
import com.meenseek.jobvis.security.CredentialCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
	properties = [
		"jobvis.crypto.key-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	],
)
@ActiveProfiles("local")
@Import(ConnectionCredentialIntegrationTests.FailingRefreshConfiguration::class)
class ConnectionCredentialIntegrationTests @Autowired constructor(
	private val connectionService: ConnectionService,
	private val credentialService: ConnectionCredentialService,
	private val refreshCoordinator: RefreshCoordinator,
	private val refreshClaimService: OAuthRefreshClaimService,
	private val connectionStateService: ConnectionStateService,
	private val credentialCipher: CredentialCipher,
	private val jdbcTemplate: JdbcTemplate,
) : PostgresIntegrationTest() {
	@Test
	fun `OAuth refresh 실패의 재승인 상태는 예외와 별도 트랜잭션으로 보존된다`() {
		val userId = UUID.randomUUID()
		val now = Instant.now()
		jdbcTemplate.update(
			"INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
			userId, Timestamp.from(now), Timestamp.from(now),
		)
		val connection = connectionService.upsertOAuth(
			userId,
			ConnectionProvider.GMAIL,
			OAuthConnectionTokens(
				"expired-access", "invalid-refresh", Instant.EPOCH,
				setOf("https://www.googleapis.com/auth/gmail.readonly"), "refresh-failure@example.com",
			),
			false,
		)

		assertThatThrownBy { credentialService.accessToken(userId, connection.id) }
			.isInstanceOf(ExternalConnectionAuthorizationException::class.java)
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT status FROM external_connections WHERE id = ?", String::class.java, connection.id,
			),
		).isEqualTo("REAUTHORIZATION_REQUIRED")
	}

	@Test
	fun `OAuth refresh 일시 장애는 연결을 유지한다`() {
		val userId = UUID.randomUUID()
		val now = Instant.now()
		jdbcTemplate.update(
			"INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
			userId, Timestamp.from(now), Timestamp.from(now),
		)
		val connection = connectionService.upsertOAuth(
			userId,
			ConnectionProvider.GMAIL,
			OAuthConnectionTokens(
				"expired-access", "transient-refresh", Instant.EPOCH,
				setOf("https://www.googleapis.com/auth/gmail.readonly"), "refresh-transient@example.com",
			),
			false,
		)
		assertThatThrownBy { credentialService.accessToken(userId, connection.id) }
			.isInstanceOf(ServiceUnavailableException::class.java)
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT status FROM external_connections WHERE id = ?", String::class.java, connection.id,
			),
		).isEqualTo("CONNECTED")
	}

	@Test
	fun `OAuth 연결은 공급자 필수 scope가 빠지면 저장하지 않는다`() {
		assertThatThrownBy {
			connectionService.upsertOAuth(
				UUID.randomUUID(),
				ConnectionProvider.OUTLOOK,
				OAuthConnectionTokens(
					"access", "refresh", Instant.now().plusSeconds(3600),
					setOf("User.Read"), "missing-scope@example.com",
				),
				false,
			)
		}.isInstanceOf(BadRequestException::class.java)
	}

	@Test
	fun `늦게 도착한 refresh 성공은 사용자의 연결 철회를 되돌리지 않는다`() {
		val userId = UUID.randomUUID()
		val now = Instant.now()
		jdbcTemplate.update(
			"INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
			userId, Timestamp.from(now), Timestamp.from(now),
		)
		val connection = connectionService.upsertOAuth(
			userId,
			ConnectionProvider.GMAIL,
			OAuthConnectionTokens(
				"expired-access", "blocking-refresh", Instant.EPOCH,
				setOf("https://www.googleapis.com/auth/gmail.readonly"), "refresh-race@example.com",
			),
			false,
		)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val future = executor.submit<String> { credentialService.accessToken(userId, connection.id) }
			check(refreshCoordinator.started.await(10, TimeUnit.SECONDS))
			connectionService.revoke(userId, connection.id)
			refreshCoordinator.release.countDown()
			assertThatThrownBy { future.get(10, TimeUnit.SECONDS) }
				.hasCauseInstanceOf(ExternalConnectionAuthorizationException::class.java)
			assertThat(
				jdbcTemplate.queryForObject(
					"SELECT status FROM external_connections WHERE id = ?", String::class.java, connection.id,
				),
			).isEqualTo("REVOKED")
		} finally {
			refreshCoordinator.release.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun `동시 OAuth refresh는 하나만 외부 호출하고 유효한 성공을 실패가 덮지 않는다`() {
		val userId = UUID.randomUUID()
		val now = Instant.now()
		jdbcTemplate.update(
			"INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
			userId, Timestamp.from(now), Timestamp.from(now),
		)
		val connection = connectionService.upsertOAuth(
			userId,
			ConnectionProvider.GMAIL,
			OAuthConnectionTokens(
				"expired-access", "single-flight-refresh", Instant.EPOCH,
				setOf("https://www.googleapis.com/auth/gmail.readonly"), "single-flight@example.com",
			),
			false,
		)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val successful = executor.submit<String> { credentialService.accessToken(userId, connection.id) }
			check(refreshCoordinator.singleFlightStarted.await(10, TimeUnit.SECONDS))

			assertThatThrownBy { credentialService.accessToken(userId, connection.id) }
				.isInstanceOf(ServiceUnavailableException::class.java)
			assertThat(refreshCoordinator.singleFlightCalls.get()).isEqualTo(1)

			refreshCoordinator.singleFlightRelease.countDown()
			assertThat(successful.get(10, TimeUnit.SECONDS)).isEqualTo("single-flight-access")
			assertThat(credentialService.accessToken(userId, connection.id)).isEqualTo("single-flight-access")
			assertThat(
				jdbcTemplate.queryForObject(
					"SELECT status FROM external_connections WHERE id = ?", String::class.java, connection.id,
				),
			).isEqualTo("CONNECTED")
			assertThat(
				jdbcTemplate.queryForObject(
					"SELECT count(*) FROM connection_refresh_claims WHERE connection_id = ?",
					Long::class.java,
					connection.id,
				),
			).isZero()
		} finally {
			refreshCoordinator.singleFlightRelease.countDown()
			executor.shutdownNow()
		}
	}

	@Test
	fun `만료되어 교체된 refresh claim의 이전 소유자는 새 소유자의 성공을 덮지 못한다`() {
		val userId = UUID.randomUUID()
		val now = Instant.now()
		jdbcTemplate.update(
			"INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
			userId, Timestamp.from(now), Timestamp.from(now),
		)
		val connection = connectionService.upsertOAuth(
			userId,
			ConnectionProvider.GMAIL,
			OAuthConnectionTokens(
				"expired-access", "replaceable-refresh", Instant.EPOCH,
				setOf("https://www.googleapis.com/auth/gmail.readonly"), "replace-claim@example.com",
			),
			false,
		)
		val firstClaim = refreshClaimService.tryClaim(userId, connection.id, connection.version, now)
			?: error("첫 refresh claim이 없습니다.")
		jdbcTemplate.update(
			"UPDATE connection_refresh_claims SET expires_at = ? WHERE connection_id = ?",
			Timestamp.from(now.minusSeconds(1)), connection.id,
		)
		val secondClaim = refreshClaimService.tryClaim(userId, connection.id, connection.version, now)
			?: error("교체 refresh claim이 없습니다.")
		assertThat(secondClaim).isNotEqualTo(firstClaim)

		assertThat(
			connectionStateService.markReauthorizationRequired(
				userId, connection.id, "LATE_INVALID_GRANT", now, connection.version, firstClaim,
			),
		).isFalse()
		assertThat(
			connectionStateService.storeRefreshedTokens(
				userId,
				connection.id,
				credentialCipher.encrypt(
					"replacement-access", ConnectionService.accessTokenContext(connection.id),
				),
				null,
				now.plusSeconds(3600),
				setOf("https://www.googleapis.com/auth/gmail.readonly"),
				now,
				connection.version,
				secondClaim,
			),
		).isEqualTo(1)
		assertThat(credentialService.accessToken(userId, connection.id)).isEqualTo("replacement-access")
		assertThat(
			jdbcTemplate.queryForObject(
				"SELECT status FROM external_connections WHERE id = ?", String::class.java, connection.id,
			),
		).isEqualTo("CONNECTED")
	}

	@TestConfiguration
	class FailingRefreshConfiguration {
		@Bean
		fun refreshCoordinator(): RefreshCoordinator = RefreshCoordinator()

		@Bean
		@Primary
		fun oauthConnectionClient(coordinator: RefreshCoordinator): OAuthConnectionClient = object : OAuthConnectionClient {
			override fun isConfigured(provider: ConnectionProvider): Boolean = true

			override fun authorizationUrl(
				provider: ConnectionProvider,
				redirectUri: String,
				state: String,
				codeChallenge: String,
			): String = "https://example.com"

			override fun exchange(
				provider: ConnectionProvider,
				redirectUri: String,
				code: String,
				codeVerifier: String,
			): OAuthConnectionTokens = error("사용하지 않습니다.")

			override fun refresh(provider: ConnectionProvider, refreshToken: String): OAuthRefreshedTokens {
				if (refreshToken == "single-flight-refresh") {
					coordinator.singleFlightCalls.incrementAndGet()
					coordinator.singleFlightStarted.countDown()
					check(coordinator.singleFlightRelease.await(10, TimeUnit.SECONDS))
					return OAuthRefreshedTokens(
						"single-flight-access", null, Instant.now().plusSeconds(3600),
						setOf("https://www.googleapis.com/auth/gmail.readonly"),
					)
				}
				if (refreshToken == "blocking-refresh") {
					coordinator.started.countDown()
					check(coordinator.release.await(10, TimeUnit.SECONDS))
					return OAuthRefreshedTokens(
						"late-access", null, Instant.now().plusSeconds(3600),
						setOf("https://www.googleapis.com/auth/gmail.readonly"),
					)
				}
				if (refreshToken == "invalid-refresh") {
					throw OAuthRefreshException(OAuthRefreshFailureDisposition.REAUTHORIZATION_REQUIRED)
				}
				throw OAuthRefreshException(OAuthRefreshFailureDisposition.TRANSIENT)
			}
		}
	}

	class RefreshCoordinator {
		val started = CountDownLatch(1)
		val release = CountDownLatch(1)
		val singleFlightStarted = CountDownLatch(1)
		val singleFlightRelease = CountDownLatch(1)
		val singleFlightCalls = AtomicInteger()
	}
}
