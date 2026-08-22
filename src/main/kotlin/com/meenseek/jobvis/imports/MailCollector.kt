package com.meenseek.jobvis.imports

import com.meenseek.jobvis.connection.ConnectionCredentialService
import com.meenseek.jobvis.connection.ConnectionProvider
import com.meenseek.jobvis.connection.ExternalConnection
import com.meenseek.jobvis.common.ExternalConnectionAuthorizationException
import jakarta.annotation.PreDestroy
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.search.AndTerm
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import jakarta.mail.search.SearchException
import jakarta.mail.search.SearchTerm
import org.eclipse.angus.mail.iap.BadCommandException
import org.eclipse.angus.mail.iap.CommandFailedException
import org.eclipse.angus.mail.iap.Response
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.protocol.IMAPResponse
import org.eclipse.angus.mail.imap.protocol.SearchSequence
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DataAccessException
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ThreadLocalRandom

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

internal fun naverReceivedDateSearchTerm(window: MailCollectionWindow): SearchTerm = AndTerm(
	ReceivedDateTerm(ComparisonTerm.GE, Date.from(window.fromInclusive.minusSeconds(SEARCH_BOUNDARY_MARGIN_SECONDS))),
	ReceivedDateTerm(ComparisonTerm.LT, Date.from(window.toExclusive.plusSeconds(SEARCH_BOUNDARY_MARGIN_SECONDS))),
)

private const val SEARCH_BOUNDARY_MARGIN_SECONDS = 24 * 60 * 60L
private const val NAVER_SEARCH_RANGE_SIZE = 20_000
private const val NAVER_MAX_SEARCH_COMMANDS = 100
private const val NAVER_MAX_SEARCHABLE_MESSAGES = NAVER_SEARCH_RANGE_SIZE * NAVER_MAX_SEARCH_COMMANDS
private const val NAVER_MAX_SEARCH_CANDIDATES = 20_000
private const val NAVER_FETCH_BATCH_SIZE = 200
private const val GMAIL_REQUEST_MAX_RETRIES = 3
private const val GMAIL_BACKOFF_MAX_MILLIS = 64_000L
private const val GMAIL_RETRY_AFTER_MAX_MILLIS = 24 * 60 * 60 * 1_000L
private const val GMAIL_INLINE_QUOTA_WAIT_MAX_MILLIS = 5_000L

internal fun boundedNaverUidBatches(
	messageCount: Int,
	connectionVersion: Long,
	searchRange: (firstMessageNumber: Int, lastMessageNumber: Int) -> LongArray,
): Sequence<LongArray> = sequence {
	if (messageCount > NAVER_MAX_SEARCHABLE_MESSAGES) {
		throw MailCollectionException(
			"NAVER_SEARCH_RANGE_LIMIT_EXCEEDED", MailFailureDisposition.RUN_ONLY,
			connectionVersion = connectionVersion,
		)
	}
	var lastMessageNumber = messageCount
	while (lastMessageNumber > 0) {
		val firstMessageNumber = maxOf(1, lastMessageNumber - NAVER_SEARCH_RANGE_SIZE + 1)
		val uids = searchRange(firstMessageNumber, lastMessageNumber)
		if (uids.any { it <= 0 }) {
			throw MailCollectionException(
				"NAVER_SEARCH_PROTOCOL_VIOLATION", MailFailureDisposition.RUN_ONLY,
				connectionVersion = connectionVersion,
			)
		}
		if (uids.size > NAVER_MAX_SEARCH_CANDIDATES) {
			throw MailCollectionException(
				"NAVER_SEARCH_CANDIDATE_LIMIT_EXCEEDED", MailFailureDisposition.RUN_ONLY,
				connectionVersion = connectionVersion,
			)
		}
		uids.sortDescending()
		var offset = 0
		while (offset < uids.size) {
			val end = minOf(offset + NAVER_FETCH_BATCH_SIZE, uids.size)
			yield(uids.copyOfRange(offset, end))
			offset = end
		}
		lastMessageNumber = firstMessageNumber - 1
	}
}

internal fun parseNaverUidSearchResponses(
	responses: Array<out Response>,
	connectionVersion: Long,
): LongArray {
	val uids = ArrayList<Long>(minOf(NAVER_FETCH_BATCH_SIZE, NAVER_MAX_SEARCH_CANDIDATES))
	for (response in responses) {
		val imapResponse = response as? IMAPResponse ?: continue
		if (!imapResponse.keyEquals("SEARCH")) continue
		while (true) {
			val uid = imapResponse.readLong()
			if (uid == -1L) break
			if (uid <= 0) {
				throw MailCollectionException(
					"NAVER_SEARCH_PROTOCOL_VIOLATION", MailFailureDisposition.RUN_ONLY,
					connectionVersion = connectionVersion,
				)
			}
			if (uids.size >= NAVER_MAX_SEARCH_CANDIDATES) {
				throw MailCollectionException(
					"NAVER_SEARCH_CANDIDATE_LIMIT_EXCEEDED", MailFailureDisposition.RUN_ONLY,
					connectionVersion = connectionVersion,
				)
			}
			uids += uid
		}
	}
	return uids.toLongArray()
}

internal fun naverUidSearchKeys(firstUid: Long, lastUid: Long): List<String> =
	listOf("UID", "$firstUid:$lastUid")

internal fun naverUidSearch(
	imapFolder: IMAPFolder,
	uidFolder: UIDFolder,
	firstMessageNumber: Int,
	lastMessageNumber: Int,
	searchTerm: SearchTerm,
	connectionVersion: Long,
): LongArray {
	val boundaryMessages = if (firstMessageNumber == lastMessageNumber) {
		arrayOf(imapFolder.getMessage(firstMessageNumber))
	} else {
		arrayOf(imapFolder.getMessage(firstMessageNumber), imapFolder.getMessage(lastMessageNumber))
	}
	imapFolder.fetch(boundaryMessages, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
	val firstUid = uidFolder.getUID(boundaryMessages.first())
	val lastUid = uidFolder.getUID(boundaryMessages.last())
	if (firstUid <= 0 || lastUid < firstUid) {
		throw MailCollectionException(
			"NAVER_SEARCH_PROTOCOL_VIOLATION", MailFailureDisposition.RUN_ONLY,
			connectionVersion = connectionVersion,
		)
	}
	val searchedUids = try {
		imapFolder.doCommand { protocol ->
			val arguments = SearchSequence(protocol).generateSequence(searchTerm, null)
			naverUidSearchKeys(firstUid, lastUid).forEach(arguments::writeAtom)
			val responses = protocol.command("UID SEARCH", arguments)
			val completion = responses.lastOrNull() ?: throw MailCollectionException(
				"NAVER_SEARCH_PROTOCOL_VIOLATION", MailFailureDisposition.RUN_ONLY,
				connectionVersion = connectionVersion,
			)
			val parsed = runCatching {
				if (completion.isOK) parseNaverUidSearchResponses(responses, connectionVersion) else LongArray(0)
			}
			protocol.notifyResponseHandlers(responses)
			protocol.handleResult(completion)
			parsed.getOrThrow()
		} as? LongArray ?: throw MailCollectionException(
			"NAVER_SEARCH_PROTOCOL_VIOLATION", MailFailureDisposition.RUN_ONLY,
			connectionVersion = connectionVersion,
		)
	} catch (exception: MailCollectionException) {
		throw exception
	} catch (exception: SearchException) {
		throw naverSearchCommandFailure(exception, connectionVersion)
	} catch (exception: MessagingException) {
		throw naverSearchCommandFailure(exception, connectionVersion)
	}
	if (searchedUids.any { it !in firstUid..lastUid }) {
		throw MailCollectionException(
			"NAVER_SEARCH_PROTOCOL_VIOLATION", MailFailureDisposition.RUN_ONLY,
			connectionVersion = connectionVersion,
		)
	}
	return searchedUids
}

internal fun registerNaverSearchUid(
	seenUids: MutableSet<Long>,
	uid: Long,
	connectionVersion: Long,
): Boolean {
	if (uid <= 0) {
		throw MailCollectionException(
			"NAVER_SEARCH_PROTOCOL_VIOLATION", MailFailureDisposition.RUN_ONLY,
			connectionVersion = connectionVersion,
		)
	}
	if (uid in seenUids) return false
	if (seenUids.size >= NAVER_MAX_SEARCH_CANDIDATES) {
		throw MailCollectionException(
			"NAVER_SEARCH_CANDIDATE_LIMIT_EXCEEDED", MailFailureDisposition.RUN_ONLY,
			connectionVersion = connectionVersion,
		)
	}
	seenUids += uid
	return true
}

internal fun naverSearchCommandFailure(
	exception: Exception,
	connectionVersion: Long,
): MailCollectionException {
	val causes = buildList {
		var current: Throwable? = exception
		while (current != null && size < 16) {
			add(current)
			current = current.cause
		}
	}
	val (errorCode, disposition) = when {
		causes.any { it is SearchException || it is BadCommandException } ->
			"NAVER_SEARCH_UNSUPPORTED" to MailFailureDisposition.RUN_ONLY
		causes.any { it is CommandFailedException } ->
			"NAVER_SEARCH_REJECTED" to MailFailureDisposition.TRANSIENT
		else -> "NAVER_READ_FAILED" to MailFailureDisposition.TRANSIENT
	}
	return MailCollectionException(errorCode, disposition, exception, connectionVersion)
}

internal fun ensureNaverImportCapacity(collectedCount: Int, maxMessages: Int, connectionVersion: Long) {
	if (collectedCount >= maxMessages) {
		throw MailCollectionException(
			"IMPORT_LIMIT_EXCEEDED", MailFailureDisposition.RUN_ONLY,
			connectionVersion = connectionVersion,
		)
	}
}

private data class IndexedTaskResult<T>(
	val index: Int,
	val value: T,
)

internal fun <T> executeFailFastInOrder(
	executor: ExecutorService,
	tasks: List<Callable<T>>,
	maxInFlight: Int,
): List<T> {
	if (tasks.isEmpty()) return emptyList()
	require(maxInFlight > 0) { "동시 실행 작업 수는 1 이상이어야 합니다." }
	val completionService = ExecutorCompletionService<IndexedTaskResult<T>>(executor)
	val futures = mutableListOf<Future<IndexedTaskResult<T>>>()
	val results = MutableList<IndexedTaskResult<T>?>(tasks.size) { null }
	var nextTaskIndex = 0
	fun submitNext() {
		val index = nextTaskIndex++
		val task = tasks[index]
		futures += completionService.submit(Callable { IndexedTaskResult(index, task.call()) })
	}
	try {
		repeat(minOf(maxInFlight, tasks.size)) { submitNext() }
		repeat(tasks.size) {
			val completed = try {
				completionService.take().get()
			} catch (exception: ExecutionException) {
				throw exception.cause ?: exception
			}
			results[completed.index] = completed
			if (nextTaskIndex < tasks.size) submitNext()
		}
		return results.map { requireNotNull(it).value }
	} finally {
		futures.forEach { future ->
			if (!future.isDone) future.cancel(true)
		}
	}
}

internal fun gmailBackoffMillis(
	retryNumber: Int,
	jitterMillis: Long,
): Long {
	require(retryNumber >= 0) { "Gmail 재시도 횟수는 음수일 수 없습니다." }
	val exponentialMillis = (1_000L shl minOf(retryNumber, 6)) + jitterMillis.coerceIn(0, 1_000)
	return exponentialMillis.coerceAtMost(GMAIL_BACKOFF_MAX_MILLIS)
}

internal data class GmailRetryAfter(
	val delayMillis: Long? = null,
	val retryAt: Instant? = null,
)

internal fun parseGmailRetryAfter(value: String?): GmailRetryAfter? {
	val retryAfter = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
	if (retryAfter.all { it in '0'..'9' }) {
		val maxSeconds = GMAIL_RETRY_AFTER_MAX_MILLIS / 1_000
		val seconds = retryAfter.toLongOrNull()
			?: return GmailRetryAfter(delayMillis = GMAIL_RETRY_AFTER_MAX_MILLIS)
		return GmailRetryAfter(delayMillis = seconds.coerceAtMost(maxSeconds).times(1_000))
	}
	val retryAt = runCatching {
		ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
	}.getOrNull() ?: return null
	return GmailRetryAfter(retryAt = retryAt)
}

fun interface MailCollector {
	fun collect(connection: ExternalConnection, dateFrom: LocalDate, dateTo: LocalDate): MailCollectionResult
}

@Component
class OfficialMailCollector(
	private val credentialService: ConnectionCredentialService,
	private val objectMapper: ObjectMapper,
	@Qualifier("externalRestClient") private val restClient: RestClient,
	private val mimeTextExtractor: BoundedMimeTextExtractor,
	private val gmailQuotaGate: GmailQuotaGate,
	@Value("\${jobvis.import.max-messages:2000}") private val maxMessages: Int,
	@Value("\${jobvis.import.gmail-fetch-concurrency:4}") private val gmailFetchConcurrency: Int,
) : MailCollector {
	private val gmailThreadCounter = AtomicInteger()

	init {
		require(maxMessages in 1..10_000) { "jobvis.import.max-messages는 1~10000이어야 합니다." }
		require(gmailFetchConcurrency in 1..16) {
			"jobvis.import.gmail-fetch-concurrency는 1~16이어야 합니다."
		}
	}

	private val gmailExecutor = Executors.newFixedThreadPool(gmailFetchConcurrency) { runnable ->
		Thread(runnable, "jobvis-gmail-metadata-${gmailThreadCounter.incrementAndGet()}").apply { isDaemon = true }
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
		val quotaAccountKey = gmailQuotaGate.accountKey(connection.accountEmail)
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
				val page = getGmailJsonWithRetry(
					uriBuilder.build().encode().toUriString(), accessToken,
					credential.connectionVersion, quotaAccountKey,
				)
				val messageIds = page.path("messages").mapNotNull { item ->
					item.path("id").asString().takeIf(String::isNotBlank)
				}.take(maxMessages - results.size)
				for ((id, message) in fetchGmailMetadata(
					messageIds, accessToken, credential.connectionVersion, quotaAccountKey,
				)) {
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
		} catch (exception: DataAccessException) {
			throw MailCollectionException(
				"GMAIL_QUOTA_STATE_UNAVAILABLE", MailFailureDisposition.TRANSIENT,
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

	private fun fetchGmailMetadata(
		messageIds: List<String>,
		accessToken: String,
		connectionVersion: Long,
		quotaAccountKey: String,
	): List<Pair<String, JsonNode>> {
		val tasks = messageIds.map { id ->
			Callable {
				val uri = UriComponentsBuilder.fromUriString(
					"https://gmail.googleapis.com/gmail/v1/users/me/messages/$id",
				)
					.queryParam("format", "metadata")
					.queryParam("metadataHeaders", "Subject")
					.queryParam("metadataHeaders", "From")
					.build().encode().toUriString()
				id to getGmailJsonWithRetry(uri, accessToken, connectionVersion, quotaAccountKey)
			}
		}
		return try {
			executeFailFastInOrder(gmailExecutor, tasks, gmailFetchConcurrency)
		} catch (exception: InterruptedException) {
			Thread.currentThread().interrupt()
			throw MailCollectionException(
				"GMAIL_READ_INTERRUPTED", MailFailureDisposition.TRANSIENT, exception,
				connectionVersion,
			)
		}
	}

	private fun getGmailJsonWithRetry(
		uri: String,
		accessToken: String,
		connectionVersion: Long,
		quotaAccountKey: String,
	): JsonNode {
		repeat(GMAIL_REQUEST_MAX_RETRIES + 1) { attempt ->
			if (!gmailQuotaGate.awaitPermit(quotaAccountKey, GMAIL_INLINE_QUOTA_WAIT_MAX_MILLIS)) {
				throw MailCollectionException(
					"GMAIL_QUOTA_DEFERRED", MailFailureDisposition.TRANSIENT,
					connectionVersion = connectionVersion,
				)
			}
			try {
				return getJson(uri, accessToken, connectionVersion)
			} catch (exception: RestClientResponseException) {
				if (!isRetryableGmailResponse(exception)) throw exception
				val clientDelayMillis = gmailBackoffMillis(
					attempt,
					ThreadLocalRandom.current().nextLong(1_001),
				)
				val retryAfter = parseGmailRetryAfter(
					exception.responseHeaders?.getFirst(HttpHeaders.RETRY_AFTER),
				)
				val delayMillis = maxOf(clientDelayMillis, retryAfter?.delayMillis ?: 0L)
				gmailQuotaGate.block(quotaAccountKey, delayMillis, retryAfter?.retryAt)
				if (attempt == GMAIL_REQUEST_MAX_RETRIES) throw exception
			}
		}
		error("도달할 수 없는 Gmail 재시도 상태입니다.")
	}

	private fun isRetryableGmailResponse(exception: RestClientResponseException): Boolean =
		exception.statusCode.value() == 429 || exception.statusCode.is5xxServerError ||
			(exception.statusCode.value() == 403 &&
				googleForbiddenDisposition(exception) == MailFailureDisposition.TRANSIENT)

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
			val inbox = store.getFolder("INBOX")
			folder = inbox
			inbox.open(Folder.READ_ONLY)
			val uidFolder = inbox as? UIDFolder
				?: throw MailCollectionException(
					"NAVER_UID_UNAVAILABLE", MailFailureDisposition.RUN_ONLY,
					connectionVersion = credential.connectionVersion,
				)
			val imapFolder = inbox as? IMAPFolder
				?: throw MailCollectionException(
					"NAVER_UID_UNAVAILABLE", MailFailureDisposition.RUN_ONLY,
					connectionVersion = credential.connectionVersion,
				)
			val window = mailCollectionWindow(dateFrom, dateTo)
			val uidValidity = uidFolder.uidValidity
			val results = mutableListOf<MailCandidate>()
			val seenUids = mutableSetOf<Long>()
			val searchTerm = naverReceivedDateSearchTerm(window)
			boundedNaverUidBatches(
				inbox.messageCount,
				credential.connectionVersion,
			) { firstMessageNumber, lastMessageNumber ->
				naverUidSearch(
					imapFolder, uidFolder, firstMessageNumber, lastMessageNumber, searchTerm,
					credential.connectionVersion,
				)
			}.forEach { searchedUids ->
				val newUids = searchedUids.filter { uid ->
					registerNaverSearchUid(seenUids, uid, credential.connectionVersion)
				}.toLongArray()
				if (newUids.isEmpty()) return@forEach
				val messages = uidFolder.getMessagesByUID(newUids).filterNotNull().toTypedArray()
				if (messages.isEmpty()) return@forEach
				inbox.fetch(messages, FetchProfile().apply {
					add(FetchProfile.Item.ENVELOPE)
					add(UIDFolder.FetchProfileItem.UID)
				})
				for (message in messages) {
					val uid = uidFolder.getUID(message)
					if (uid !in newUids) {
						throw MailCollectionException(
							"NAVER_SEARCH_PROTOCOL_VIOLATION", MailFailureDisposition.RUN_ONLY,
							connectionVersion = credential.connectionVersion,
						)
					}
					val receivedAt = (message.receivedDate ?: message.sentDate)?.toInstant() ?: continue
					if (!window.contains(receivedAt)) continue
					ensureNaverImportCapacity(results.size, maxMessages, credential.connectionVersion)
					results += MailCandidate(
						ConnectionProvider.NAVER,
						"${inbox.fullName}:$uidValidity:$uid",
						message.subject.orEmpty(),
						message.from?.joinToString(", ").orEmpty(),
						receivedAt,
						mimeTextExtractor.extract(message).replace(HTML_TAG, " ")
							.replace(WHITESPACE, " ").trim().take(PREVIEW_LIMIT),
					)
				}
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

	@PreDestroy
	fun shutdownGmailExecutor() {
		gmailExecutor.shutdownNow()
		try {
			gmailExecutor.awaitTermination(5, TimeUnit.SECONDS)
		} catch (_: InterruptedException) {
			Thread.currentThread().interrupt()
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
		val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
		val HTML_TAG = Regex("<[^>]+>")
		val WHITESPACE = Regex("\\s+")
		val GOOGLE_TRANSIENT_REASONS = setOf(
			"rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded", "quotaExceeded", "backendError",
		)
		val GOOGLE_AUTH_REASONS = setOf("authError", "insufficientPermissions")
	}
}
