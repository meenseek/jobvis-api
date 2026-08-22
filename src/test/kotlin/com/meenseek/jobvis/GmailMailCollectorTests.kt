package com.meenseek.jobvis

import com.meenseek.jobvis.connection.AuthorizedCredential
import com.meenseek.jobvis.connection.ConnectionCredentialService
import com.meenseek.jobvis.connection.ConnectionProvider
import com.meenseek.jobvis.connection.ExternalConnection
import com.meenseek.jobvis.imports.BoundedMimeTextExtractor
import com.meenseek.jobvis.imports.GmailQuotaGate
import com.meenseek.jobvis.imports.MailCollectionException
import com.meenseek.jobvis.imports.MailFailureDisposition
import com.meenseek.jobvis.imports.OfficialMailCollector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class GmailMailCollectorTests {
	@Test
	fun `Gmail list도 account quota gate와 Retry-After 재시도를 사용한다`() {
		val fixture = fixture()
		val builder = RestClient.builder()
		val server = MockRestServiceServer.bindTo(builder).build()
		server.expect(ExpectedCount.times(4)) { request ->
			assertThat(request.uri.host).isEqualTo("gmail.googleapis.com")
			assertThat(request.uri.path).isEqualTo("/gmail/v1/users/me/messages")
		}.andRespond(
			withStatus(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, "120")
				.body("""{"error":{"code":429}}"""),
		)
		val collector = fixture.collector(builder.build())
		try {
			assertThatThrownBy { collector.collect(fixture.connection, DATE, DATE) }
				.isInstanceOfSatisfying(MailCollectionException::class.java) { exception ->
					assertThat(exception.errorCode).isEqualTo("GMAIL_READ_FAILED")
					assertThat(exception.disposition).isEqualTo(MailFailureDisposition.TRANSIENT)
				}
			server.verify()
			Mockito.verify(fixture.quotaGate, Mockito.times(4)).awaitPermit(fixture.accountKey, 5_000)
			Mockito.verify(fixture.quotaGate, Mockito.times(4)).block(fixture.accountKey, 120_000, null)
		} finally {
			collector.shutdownGmailExecutor()
		}
	}

	@Test
	fun `Gmail quota gate의 DB 일시 장애는 transient로 분류한다`() {
		val fixture = fixture()
		Mockito.`when`(fixture.quotaGate.awaitPermit(fixture.accountKey, 5_000))
			.thenThrow(TransientDataAccessResourceException("quota DB unavailable"))
		val collector = fixture.collector(RestClient.create())
		try {
			assertThatThrownBy { collector.collect(fixture.connection, DATE, DATE) }
				.isInstanceOfSatisfying(MailCollectionException::class.java) { exception ->
					assertThat(exception.errorCode).isEqualTo("GMAIL_QUOTA_STATE_UNAVAILABLE")
					assertThat(exception.disposition).isEqualTo(MailFailureDisposition.TRANSIENT)
				}
		} finally {
			collector.shutdownGmailExecutor()
		}
	}

	private fun fixture(): Fixture {
		val userId = UUID.randomUUID()
		val connection = ExternalConnection.createOAuth(
			UUID.randomUUID(), userId, ConnectionProvider.GMAIL, "shared@example.com",
			"encrypted-access", "encrypted-refresh", Instant.parse("2026-08-22T00:00:00Z"),
			setOf("https://www.googleapis.com/auth/gmail.readonly"), true,
			Instant.parse("2026-08-21T00:00:00Z"),
		)
		val credentialService = Mockito.mock(ConnectionCredentialService::class.java)
		Mockito.`when`(credentialService.authorizedAccessToken(userId, connection.id))
			.thenReturn(AuthorizedCredential("access-token", connection.version))
		val quotaGate = Mockito.mock(GmailQuotaGate::class.java)
		val accountKey = "a".repeat(64)
		Mockito.`when`(quotaGate.accountKey(connection.accountEmail)).thenReturn(accountKey)
		Mockito.`when`(quotaGate.awaitPermit(accountKey, 5_000)).thenReturn(true)
		return Fixture(connection, credentialService, quotaGate, accountKey)
	}

	private data class Fixture(
		val connection: ExternalConnection,
		val credentialService: ConnectionCredentialService,
		val quotaGate: GmailQuotaGate,
		val accountKey: String,
	) {
		fun collector(restClient: RestClient): OfficialMailCollector = OfficialMailCollector(
			credentialService,
			ObjectMapper(),
			restClient,
			BoundedMimeTextExtractor(),
			quotaGate,
			2_000,
			1,
		)
	}

	private companion object {
		val DATE: LocalDate = LocalDate.parse("2026-08-21")
	}
}
