package com.meenseek.jobvis.imports

import com.meenseek.jobvis.connection.ConnectionCredentialService
import com.meenseek.jobvis.connection.ConnectionProvider
import com.meenseek.jobvis.connection.ExternalConnection
import com.meenseek.jobvis.common.ExternalConnectionAuthorizationException
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Properties

class MailCollectionException(
	val errorCode: String,
	val disposition: MailFailureDisposition,
	cause: Throwable? = null,
	val connectionVersion: Long? = null,
) : RuntimeException(errorCode, cause)

enum class MailFailureDisposition { REAUTHORIZATION_REQUIRED, TRANSIENT, RUN_ONLY, CONNECTION_ERROR }

data class MailCollectionResult(
	val candidates: List<MailCandidate>,
	val connectionVersion: Long,
)

internal data class MailCollectionWindow(
	val fromInclusive: Instant,
	val toExclusive: Instant,
) {
	val gmailAfterEpochSecond: Long get() = fromInclusive.minusSeconds(1).epochSecond
	val gmailBeforeEpochSecond: Long get() = toExclusive.epochSecond

	fun contains(receivedAt: Instant): Boolean =
		!receivedAt.isBefore(fromInclusive) && receivedAt.isBefore(toExclusive)
}

internal fun mailCollectionWindow(dateFrom: LocalDate, dateTo: LocalDate): MailCollectionWindow =
	MailCollectionWindow(
		dateFrom.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),
		dateTo.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(),
	)

fun interface MailCollector {
	fun collect(connection: ExternalConnection, dateFrom: LocalDate, dateTo: LocalDate): MailCollectionResult
}

@Component
class OfficialMailCollector(
	private val credentialService: ConnectionCredentialService,
	private val objectMapper: ObjectMapper,
	@Qualifier("externalRestClient") private val restClient: RestClient,
	private val mimeTextExtractor: BoundedMimeTextExtractor,
	@Value("\${jobvis.import.max-messages:2000}") private val maxMessages: Int,
) : MailCollector {
	init {
		require(maxMessages in 1..10_000) { "jobvis.import.max-messages는 1~10000이어야 합니다." }
	}

	override fun collect(
		connection: ExternalConnection,
		dateFrom: LocalDate,
		dateTo: LocalDate,
	): MailCollectionResult = when (connection.provider) {
		ConnectionProvider.GMAIL -> collectGmail(connection, dateFrom, dateTo)
		ConnectionProvider.OUTLOOK -> collectOutlook(connection, dateFrom, dateTo)
		ConnectionProvider.NAVER -> collectNaver(connection, dateFrom, dateTo)
		ConnectionProvider.GOOGLE_CALENDAR -> throw MailCollectionException(
			"NOT_A_MAIL_CONNECTION", MailFailureDisposition.CONNECTION_ERROR,
			connectionVersion = connection.version,
		)
	}

	private fun collectGmail(
		connection: ExternalConnection,
		dateFrom: LocalDate,
		dateTo: LocalDate,
	): MailCollectionResult {
		val credential = try {
			credentialService.authorizedAccessToken(connection.userId, connection.id)
		} catch (exception: ExternalConnectionAuthorizationException) {
			throw MailCollectionException(
				"GMAIL_REAUTHORIZATION_REQUIRED", MailFailureDisposition.REAUTHORIZATION_REQUIRED,
				exception, exception.connectionVersion,
			)
		}
		val accessToken = credential.value
		val window = mailCollectionWindow(dateFrom, dateTo)
		val results = mutableListOf<MailCandidate>()
		var pageToken: String? = null
		try {
			do {
				val uriBuilder = UriComponentsBuilder.fromUriString(
					"https://gmail.googleapis.com/gmail/v1/users/me/messages",
				)
					.queryParam("maxResults", minOf(100, maxMessages - results.size))
					.queryParam(
						"q", "after:${window.gmailAfterEpochSecond} before:${window.gmailBeforeEpochSecond}",
					)
				if (pageToken != null) uriBuilder.queryParam("pageToken", pageToken)
				val page = getJson(uriBuilder.build().encode().toUriString(), accessToken, credential.connectionVersion)
				for (item in page.path("messages")) {
					if (results.size >= maxMessages) break
					val id = item.path("id").asString().takeIf(String::isNotBlank) ?: continue
					val messageUri = UriComponentsBuilder.fromUriString(
						"https://gmail.googleapis.com/gmail/v1/users/me/messages/$id",
					)
						.queryParam("format", "metadata")
						.queryParam("metadataHeaders", "Subject")
						.queryParam("metadataHeaders", "From")
						.build().encode().toUriString()
					val message = getJson(messageUri, accessToken, credential.connectionVersion)
					val headers = message.path("payload").path("headers").associate { header ->
						header.path("name").asString().lowercase() to header.path("value").asString()
					}
					val receivedAt = message.path("internalDate").asString().toLongOrNull()?.let(Instant::ofEpochMilli)
						?: continue
					if (!window.contains(receivedAt)) continue
					results += MailCandidate(
						ConnectionProvider.GMAIL,
						id,
						headers["subject"].orEmpty(),
						headers["from"].orEmpty(),
						receivedAt,
						message.path("snippet").asString().take(PREVIEW_LIMIT),
					)
				}
				pageToken = page.path("nextPageToken").asString().takeIf(String::isNotBlank)
			} while (pageToken != null && results.size < maxMessages)
		} catch (exception: MailCollectionException) {
			throw exception
		} catch (exception: ExternalConnectionAuthorizationException) {
			throw MailCollectionException(
				"GMAIL_REAUTHORIZATION_REQUIRED", MailFailureDisposition.REAUTHORIZATION_REQUIRED,
				exception, credential.connectionVersion,
			)
		} catch (exception: RestClientException) {
			throw mailHttpFailure("GMAIL_READ_FAILED", exception, google = true, credential.connectionVersion)
		} catch (exception: Exception) {
			throw MailCollectionException(
				"GMAIL_PROCESSING_FAILED", MailFailureDisposition.RUN_ONLY,
				exception, credential.connectionVersion,
			)
		}
		if (pageToken != null) {
			throw MailCollectionException(
				"IMPORT_LIMIT_EXCEEDED", MailFailureDisposition.RUN_ONLY,
				connectionVersion = credential.connectionVersion,
			)
		}
		return MailCollectionResult(results, credential.connectionVersion)
	}

	private fun collectOutlook(
		connection: ExternalConnection,
		dateFrom: LocalDate,
		dateTo: LocalDate,
	): MailCollectionResult {
		val credential = try {
			credentialService.authorizedAccessToken(connection.userId, connection.id)
		} catch (exception: ExternalConnectionAuthorizationException) {
			throw MailCollectionException(
				"OUTLOOK_REAUTHORIZATION_REQUIRED", MailFailureDisposition.REAUTHORIZATION_REQUIRED,
				exception, exception.connectionVersion,
			)
		}
		val accessToken = credential.value
		val window = mailCollectionWindow(dateFrom, dateTo)
		val results = mutableListOf<MailCandidate>()
		var nextUri: String? = UriComponentsBuilder.fromUriString("https://graph.microsoft.com/v1.0/me/messages")
			.queryParam("\$select", "id,subject,from,receivedDateTime,bodyPreview")
			.queryParam(
				"\$filter", "receivedDateTime ge ${window.fromInclusive} and receivedDateTime lt ${window.toExclusive}",
			)
			.queryParam("\$orderby", "receivedDateTime asc")
			.queryParam("\$top", minOf(100, maxMessages))
			.build().encode().toUriString()
		try {
			while (nextUri != null && results.size < maxMessages) {
				if (!nextUri.startsWith(GRAPH_MESSAGES_PREFIX)) {
					throw MailCollectionException(
						"OUTLOOK_PAGING_URI_REJECTED", MailFailureDisposition.RUN_ONLY,
						connectionVersion = credential.connectionVersion,
					)
				}
				val page = getJson(
					nextUri, accessToken, credential.connectionVersion, immutableOutlookIds = true,
				)
				for (message in page.path("value")) {
					if (results.size >= maxMessages) break
					val id = message.path("id").asString().takeIf(String::isNotBlank) ?: continue
					val receivedAt = runCatching {
						Instant.parse(message.path("receivedDateTime").asString())
					}.getOrNull() ?: continue
					if (!window.contains(receivedAt)) continue
					results += MailCandidate(
						ConnectionProvider.OUTLOOK,
						id,
						message.path("subject").asString(),
						message.path("from").path("emailAddress").path("address").asString(),
						receivedAt,
						message.path("bodyPreview").asString().take(PREVIEW_LIMIT),
					)
				}
				nextUri = page.path("@odata.nextLink").asString().takeIf(String::isNotBlank)
			}
		} catch (exception: MailCollectionException) {
			throw exception
		} catch (exception: ExternalConnectionAuthorizationException) {
			throw MailCollectionException(
				"OUTLOOK_REAUTHORIZATION_REQUIRED", MailFailureDisposition.REAUTHORIZATION_REQUIRED,
				exception, credential.connectionVersion,
			)
		} catch (exception: RestClientException) {
			throw mailHttpFailure("OUTLOOK_READ_FAILED", exception, google = false, credential.connectionVersion)
		} catch (exception: Exception) {
			throw MailCollectionException(
				"OUTLOOK_PROCESSING_FAILED", MailFailureDisposition.RUN_ONLY,
				exception, credential.connectionVersion,
			)
		}
		if (nextUri != null) {
			throw MailCollectionException(
				"IMPORT_LIMIT_EXCEEDED", MailFailureDisposition.RUN_ONLY,
				connectionVersion = credential.connectionVersion,
			)
		}
		return MailCollectionResult(results, credential.connectionVersion)
	}

	private fun collectNaver(
		connection: ExternalConnection,
		dateFrom: LocalDate,
		dateTo: LocalDate,
	): MailCollectionResult {
		val credential = try {
			credentialService.authorizedAppPassword(connection.userId, connection.id)
		} catch (exception: ExternalConnectionAuthorizationException) {
			throw MailCollectionException(
				"NAVER_REAUTHORIZATION_REQUIRED", MailFailureDisposition.REAUTHORIZATION_REQUIRED,
				exception, exception.connectionVersion,
			)
		}
		val appPassword = credential.value
		val properties = Properties().apply {
			setProperty("mail.store.protocol", "imaps")
			setProperty("mail.imaps.host", "imap.naver.com")
			setProperty("mail.imaps.port", "993")
			setProperty("mail.imaps.ssl.enable", "true")
			setProperty("mail.imaps.connectiontimeout", "10000")
			setProperty("mail.imaps.timeout", "30000")
			setProperty("mail.imaps.writetimeout", "10000")
		}
		val store = Session.getInstance(properties).getStore("imaps")
		var folder: Folder? = null
		try {
			store.connect("imap.naver.com", 993, connection.accountEmail, appPassword)
			folder = store.getFolder("INBOX")
			folder.open(Folder.READ_ONLY)
			val uidFolder = folder as? UIDFolder
				?: throw MailCollectionException(
					"NAVER_UID_UNAVAILABLE", MailFailureDisposition.RUN_ONLY,
					connectionVersion = credential.connectionVersion,
				)
			val window = mailCollectionWindow(dateFrom, dateTo)
			val uidValidity = uidFolder.uidValidity
			val results = mutableListOf<MailCandidate>()
			val maxScanned = minOf(maxMessages * 10, MAX_NAVER_SCAN)
			var scanned = 0
			var lastMessageNumber = folder.messageCount
			while (lastMessageNumber > 0 && scanned < maxScanned) {
				val firstMessageNumber = maxOf(1, lastMessageNumber - NAVER_BATCH_SIZE + 1)
				val messages = folder.getMessages(firstMessageNumber, lastMessageNumber)
				folder.fetch(messages, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE) })
				for (index in messages.indices.reversed()) {
					val message = messages[index]
					scanned++
					val receivedAt = (message.receivedDate ?: message.sentDate)?.toInstant() ?: continue
					if (!window.contains(receivedAt)) continue
					if (results.size >= maxMessages) {
						throw MailCollectionException(
							"IMPORT_LIMIT_EXCEEDED", MailFailureDisposition.RUN_ONLY,
							connectionVersion = credential.connectionVersion,
						)
					}
					val uid = uidFolder.getUID(message)
					results += MailCandidate(
						ConnectionProvider.NAVER,
						"${folder.fullName}:$uidValidity:$uid",
						message.subject.orEmpty(),
						message.from?.joinToString(", ").orEmpty(),
						receivedAt,
						mimeTextExtractor.extract(message).replace(HTML_TAG, " ")
							.replace(WHITESPACE, " ").trim().take(PREVIEW_LIMIT),
					)
				}
				lastMessageNumber = firstMessageNumber - 1
			}
			if (lastMessageNumber > 0 && scanned >= maxScanned) {
				throw MailCollectionException(
					"NAVER_SCAN_LIMIT_EXCEEDED", MailFailureDisposition.RUN_ONLY,
					connectionVersion = credential.connectionVersion,
				)
			}
			return MailCollectionResult(results.sortedBy(MailCandidate::receivedAt), credential.connectionVersion)
		} catch (exception: MailCollectionException) {
			throw exception
		} catch (exception: ExternalConnectionAuthorizationException) {
			throw MailCollectionException(
				"NAVER_REAUTHORIZATION_REQUIRED", MailFailureDisposition.REAUTHORIZATION_REQUIRED,
				exception, credential.connectionVersion,
			)
		} catch (exception: AuthenticationFailedException) {
			throw MailCollectionException(
				"NAVER_AUTHENTICATION_FAILED", MailFailureDisposition.REAUTHORIZATION_REQUIRED,
				exception, credential.connectionVersion,
			)
		} catch (exception: Exception) {
			throw MailCollectionException(
				"NAVER_READ_FAILED", MailFailureDisposition.TRANSIENT, exception, credential.connectionVersion,
			)
		} finally {
			if (folder?.isOpen == true) runCatching { folder.close(false) }
			if (store.isConnected) runCatching(store::close)
		}
	}

	private fun getJson(
		uri: String,
		accessToken: String,
		connectionVersion: Long,
		immutableOutlookIds: Boolean = false,
	): JsonNode {
		val request = restClient.get()
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
		if (immutableOutlookIds) request.header("Prefer", "IdType=\"ImmutableId\"")
		return request.retrieve()
		.body(String::class.java)
		?.let(objectMapper::readTree)
		?: throw MailCollectionException(
			"EMPTY_MAIL_RESPONSE", MailFailureDisposition.TRANSIENT, connectionVersion = connectionVersion,
		)
	}

	private fun mailHttpFailure(
		errorCode: String,
		exception: RestClientException,
		google: Boolean,
		connectionVersion: Long,
	): MailCollectionException {
		val disposition = if (exception is RestClientResponseException) {
			when {
				exception.statusCode.value() == 401 ->
					MailFailureDisposition.REAUTHORIZATION_REQUIRED
				exception.statusCode.value() == 403 && google -> googleForbiddenDisposition(exception)
				exception.statusCode.value() == 403 -> MailFailureDisposition.REAUTHORIZATION_REQUIRED
				exception.statusCode.value() == 429 || exception.statusCode.is5xxServerError ->
					MailFailureDisposition.TRANSIENT
				else -> MailFailureDisposition.RUN_ONLY
			}
		} else {
			MailFailureDisposition.TRANSIENT
		}
		return MailCollectionException(errorCode, disposition, exception, connectionVersion)
	}

	private fun googleForbiddenDisposition(exception: RestClientResponseException): MailFailureDisposition {
		val reasons: Set<String> = runCatching {
			buildSet {
				objectMapper.readTree(exception.responseBodyAsString).path("error").path("errors")
					.forEach { node -> add(node.path("reason").asString()) }
			}
		}.getOrDefault(emptySet())
		return when {
			reasons.any { it in GOOGLE_TRANSIENT_REASONS } -> MailFailureDisposition.TRANSIENT
			reasons.any { it in GOOGLE_AUTH_REASONS } -> MailFailureDisposition.REAUTHORIZATION_REQUIRED
			else -> MailFailureDisposition.RUN_ONLY
		}
	}

	private companion object {
		const val PREVIEW_LIMIT = 4000
		const val GRAPH_MESSAGES_PREFIX = "https://graph.microsoft.com/v1.0/"
		const val NAVER_BATCH_SIZE = 200
		const val MAX_NAVER_SCAN = 20_000
		val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
		val HTML_TAG = Regex("<[^>]+>")
		val WHITESPACE = Regex("\\s+")
		val GOOGLE_TRANSIENT_REASONS = setOf(
			"rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded", "quotaExceeded", "backendError",
		)
		val GOOGLE_AUTH_REASONS = setOf("authError", "insufficientPermissions")
	}
}
