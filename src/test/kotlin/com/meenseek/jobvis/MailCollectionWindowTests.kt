package com.meenseek.jobvis

import com.meenseek.jobvis.imports.mailCollectionWindow
import com.meenseek.jobvis.imports.naverReceivedDateSearchTerm
import com.meenseek.jobvis.imports.naverSearchCommandFailure
import com.meenseek.jobvis.imports.naverUidSearchKeys
import com.meenseek.jobvis.imports.boundedNaverUidBatches
import com.meenseek.jobvis.imports.ensureNaverImportCapacity
import com.meenseek.jobvis.imports.executeFailFastInOrder
import com.meenseek.jobvis.imports.gmailBackoffMillis
import com.meenseek.jobvis.imports.parseGmailRetryAfter
import com.meenseek.jobvis.imports.MailCollectionException
import com.meenseek.jobvis.imports.MailFailureDisposition
import com.meenseek.jobvis.imports.parseNaverUidSearchResponses
import com.meenseek.jobvis.imports.registerNaverSearchUid
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.search.SearchException
import org.eclipse.angus.mail.iap.BadCommandException
import org.eclipse.angus.mail.iap.CommandFailedException
import org.eclipse.angus.mail.iap.Response
import org.eclipse.angus.mail.imap.protocol.IMAPResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class MailCollectionWindowTests {
	@Test
	fun `Gmail client backoff는 jitter를 포함하고 64초로 제한한다`() {
		assertThat(gmailBackoffMillis(0, 250)).isEqualTo(1_250)
		assertThat(gmailBackoffMillis(10, 1_000)).isEqualTo(64_000)
	}

	@Test
	fun `Gmail Retry-After는 초와 HTTP 날짜를 해석하고 24시간으로 제한한다`() {
		val now = Instant.parse("2026-08-21T00:00:00Z")
		val httpDate = ZonedDateTime.ofInstant(now.plusSeconds(120), ZoneOffset.UTC)
			.format(DateTimeFormatter.RFC_1123_DATE_TIME)

		assertThat(parseGmailRetryAfter("120")?.delayMillis).isEqualTo(120_000)
		assertThat(parseGmailRetryAfter(httpDate)?.retryAt).isEqualTo(now.plusSeconds(120))
		assertThat(parseGmailRetryAfter("90000")?.delayMillis).isEqualTo(86_400_000)
		assertThat(parseGmailRetryAfter("9223372036854775808")?.delayMillis).isEqualTo(86_400_000)
		assertThat(parseGmailRetryAfter("invalid")).isNull()
	}

	@Test
	fun `Gmail 병렬 조회는 첫 실패를 즉시 전달하고 남은 작업을 취소한다`() {
		val executor = Executors.newFixedThreadPool(2)
		val blockingTaskStarted = CountDownLatch(1)
		val blockingTaskInterrupted = CountDownLatch(1)
		try {
			assertThatThrownBy {
				executeFailFastInOrder(
					executor,
					listOf<Callable<String>>(
						Callable {
							check(blockingTaskStarted.await(1, TimeUnit.SECONDS))
							throw IllegalStateException("metadata failed")
						},
						Callable {
							blockingTaskStarted.countDown()
							try {
								CountDownLatch(1).await()
							} catch (exception: InterruptedException) {
								blockingTaskInterrupted.countDown()
								throw exception
							}
							"unreachable"
						},
						Callable { "queued" },
					),
					2,
				)
			}.isInstanceOf(IllegalStateException::class.java)
				.hasMessage("metadata failed")

			assertThat(blockingTaskInterrupted.await(1, TimeUnit.SECONDS)).isTrue()
		} finally {
			executor.shutdownNow()
			assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue()
		}
	}

	@Test
	fun `Gmail 공유 executor에는 호출별 in-flight 작업만 제출한다`() {
		val executor = Executors.newFixedThreadPool(1) as ThreadPoolExecutor
		val caller = Executors.newSingleThreadExecutor()
		val firstStarted = CountDownLatch(1)
		val releaseFirst = CountDownLatch(1)
		try {
			val result = caller.submit<List<Int>> {
				executeFailFastInOrder(
					executor,
					List(10) { index ->
						Callable {
							if (index == 0) {
								firstStarted.countDown()
								check(releaseFirst.await(2, TimeUnit.SECONDS))
							}
							index
						}
					},
					2,
				)
			}

			assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue()
			assertThat(executor.queue).hasSize(1)
			releaseFirst.countDown()
			assertThat(result.get(2, TimeUnit.SECONDS)).containsExactlyElementsOf(0..9)
		} finally {
			releaseFirst.countDown()
			caller.shutdownNow()
			executor.shutdownNow()
			assertThat(caller.awaitTermination(1, TimeUnit.SECONDS)).isTrue()
			assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue()
		}
	}

	@Test
	fun `Gmail 검색은 시작 경계를 1초 앞당기고 실제 수신 시각은 반개구간으로 필터링한다`() {
		val window = mailCollectionWindow(LocalDate.parse("2026-08-17"), LocalDate.parse("2026-08-17"))

		assertThat(window.fromInclusive).isEqualTo(Instant.parse("2026-08-16T15:00:00Z"))
		assertThat(window.gmailAfterEpochSecond).isEqualTo(window.fromInclusive.epochSecond - 1)
		assertThat(window.contains(window.fromInclusive.minusSeconds(1))).isFalse()
		assertThat(window.contains(window.fromInclusive)).isTrue()
		assertThat(window.contains(window.toExclusive.minusMillis(1))).isTrue()
		assertThat(window.contains(window.toExclusive)).isFalse()
	}

	@Test
	fun `Naver 검색은 전체 메일함 대신 요청 날짜 범위를 서버 검색 조건으로 제한한다`() {
		val window = mailCollectionWindow(LocalDate.parse("2026-08-17"), LocalDate.parse("2026-08-17"))
		val term = naverReceivedDateSearchTerm(window)

		assertThat(term.match(messageAt("2026-08-17T03:00:00Z"))).isTrue()
		assertThat(term.match(messageAt("2026-08-14T03:00:00Z"))).isFalse()
		assertThat(term.match(messageAt("2026-08-20T03:00:00Z"))).isFalse()
	}

	private fun messageAt(value: String): Message = Mockito.mock(Message::class.java).also { message ->
		Mockito.`when`(message.receivedDate).thenReturn(Date.from(Instant.parse(value)))
	}

	@Test
	fun `Naver 검색은 최신 구간부터 고정 크기로 실행하고 결과를 200개씩만 객체화한다`() {
		val searchedRanges = mutableListOf<IntRange>()
		val batches = boundedNaverUidBatches(45_000, 7) { first, last ->
			searchedRanges += first..last
			LongArray(450) { index -> 4_000_000_000L + index }
		}.toList()

		assertThat(searchedRanges).containsExactly(25_001..45_000, 5_001..25_000, 1..5_000)
		assertThat(batches).allSatisfy { batch -> assertThat(batch.size).isLessThanOrEqualTo(200) }
		assertThat(batches.first().first()).isEqualTo(4_000_000_449L)
	}

	@Test
	fun `Naver 검색은 consumer가 중단하면 이전 구간을 추가 검색하지 않는다`() {
		var searchCalls = 0
		val batches = boundedNaverUidBatches(40_000, 7) { _, last ->
			searchCalls++
			longArrayOf(4_000_000_000L + last)
		}

		assertThat(batches.first()).containsExactly(4_000_040_000L)
		assertThat(searchCalls).isEqualTo(1)
	}

	@Test
	fun `Naver 검색은 mailbox raw result와 UID 유효성을 각각 제한한다`() {
		assertThatThrownBy {
			boundedNaverUidBatches(2_000_001, 7) { _, _ -> LongArray(0) }.toList()
		}.isInstanceOfSatisfying(MailCollectionException::class.java) { exception ->
			assertThat(exception.errorCode).isEqualTo("NAVER_SEARCH_RANGE_LIMIT_EXCEEDED")
		}
		assertThatThrownBy {
			boundedNaverUidBatches(20_000, 7) { _, _ -> longArrayOf(0) }.toList()
		}.isInstanceOfSatisfying(MailCollectionException::class.java) { exception ->
			assertThat(exception.errorCode).isEqualTo("NAVER_SEARCH_PROTOCOL_VIOLATION")
		}
		assertThatThrownBy {
			boundedNaverUidBatches(20_000, 7) { _, _ -> LongArray(20_001) { 1 } }.toList()
		}.isInstanceOfSatisfying(MailCollectionException::class.java) { exception ->
			assertThat(exception.errorCode).isEqualTo("NAVER_SEARCH_CANDIDATE_LIMIT_EXCEEDED")
		}
	}

	@Test
	fun `Naver UID SEARCH 응답은 unsigned 32 bit UID를 그대로 보존한다`() {
		val responses = arrayOf<Response>(IMAPResponse("* SEARCH 12 4294967295"))

		assertThat(naverUidSearchKeys(4_000_000_000L, 4_000_019_999L))
			.containsExactly("UID", "4000000000:4000019999")
		assertThat(parseNaverUidSearchResponses(responses, 7))
			.containsExactly(12L, 4_294_967_295L)
	}

	@Test
	fun `Naver 검색은 sequence number가 이동해도 stable UID 중복을 세지 않는다`() {
		val seenUids = mutableSetOf<Long>()
		assertThat(registerNaverSearchUid(seenUids, 10, 7)).isTrue()
		assertThat(registerNaverSearchUid(seenUids, 10, 7)).isFalse()
		for (uid in 11L..20_009L) registerNaverSearchUid(seenUids, uid, 7)

		assertThatThrownBy { registerNaverSearchUid(seenUids, 20_010, 7) }
			.isInstanceOfSatisfying(MailCollectionException::class.java) { exception ->
				assertThat(exception.errorCode).isEqualTo("NAVER_SEARCH_CANDIDATE_LIMIT_EXCEEDED")
			}
		assertThatThrownBy { registerNaverSearchUid(mutableSetOf(), 0, 7) }
			.isInstanceOfSatisfying(MailCollectionException::class.java) { exception ->
				assertThat(exception.errorCode).isEqualTo("NAVER_SEARCH_PROTOCOL_VIOLATION")
			}
	}

	@Test
	fun `Naver SEARCH 거부와 일시적 read 실패를 구분한다`() {
		val unsupported = naverSearchCommandFailure(
			MessagingException("bad", BadCommandException("bad command")), 7,
		)
		assertThat(unsupported.errorCode).isEqualTo("NAVER_SEARCH_UNSUPPORTED")
		assertThat(unsupported.disposition).isEqualTo(MailFailureDisposition.RUN_ONLY)

		val rejected = naverSearchCommandFailure(
			MessagingException("no", CommandFailedException("command failed")), 7,
		)
		assertThat(rejected.errorCode).isEqualTo("NAVER_SEARCH_REJECTED")
		assertThat(rejected.disposition).isEqualTo(MailFailureDisposition.TRANSIENT)

		val localFailure = naverSearchCommandFailure(SearchException("invalid term"), 7)
		assertThat(localFailure.errorCode).isEqualTo("NAVER_SEARCH_UNSUPPORTED")

		val transient = naverSearchCommandFailure(MessagingException("connection reset"), 7)
		assertThat(transient.errorCode).isEqualTo("NAVER_READ_FAILED")
		assertThat(transient.disposition).isEqualTo(MailFailureDisposition.TRANSIENT)
	}

	@Test
	fun `Naver exact match는 maxMessages 다음 건에서 중단한다`() {
		ensureNaverImportCapacity(1, 2, 7)
		assertThatThrownBy { ensureNaverImportCapacity(2, 2, 7) }
			.isInstanceOfSatisfying(MailCollectionException::class.java) { exception ->
				assertThat(exception.errorCode).isEqualTo("IMPORT_LIMIT_EXCEEDED")
			}
	}
}
