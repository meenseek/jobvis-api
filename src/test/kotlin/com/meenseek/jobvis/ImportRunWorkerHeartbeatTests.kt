package com.meenseek.jobvis

import com.meenseek.jobvis.connection.ExternalConnectionRepository
import com.meenseek.jobvis.imports.ClaimedImportRun
import com.meenseek.jobvis.imports.ImportRunClaimService
import com.meenseek.jobvis.imports.ImportRunCompletionService
import com.meenseek.jobvis.imports.ImportRunRepository
import com.meenseek.jobvis.imports.ImportRunWorker
import com.meenseek.jobvis.imports.MailCollector
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ImportRunWorkerHeartbeatTests {
	@Test
	fun `한 claim의 지연된 heartbeat가 다른 claim의 갱신을 막지 않는다`() {
		val firstClaim = ClaimedImportRun(UUID.randomUUID(), UUID.randomUUID())
		val secondClaim = ClaimedImportRun(UUID.randomUUID(), UUID.randomUUID())
		val firstStarted = CountDownLatch(1)
		val releaseFirst = CountDownLatch(1)
		val secondRan = CountDownLatch(1)
		val claimService = object : ImportRunClaimService(Mockito.mock(JdbcTemplate::class.java)) {
			override fun heartbeat(claim: ClaimedImportRun): Boolean = when (claim) {
				firstClaim -> {
					firstStarted.countDown()
					check(releaseFirst.await(10, TimeUnit.SECONDS))
					true
				}
				secondClaim -> {
					secondRan.countDown()
					true
				}
				else -> false
			}
		}

		val worker = ImportRunWorker(
			claimService,
			Mockito.mock(ImportRunRepository::class.java),
			Mockito.mock(ExternalConnectionRepository::class.java),
			Mockito.mock(MailCollector::class.java),
			Mockito.mock(ImportRunCompletionService::class.java),
			2,
			Duration.ZERO,
			Duration.ofSeconds(1),
			2,
		)
		var firstFuture: ScheduledFuture<*>? = null
		var secondFuture: ScheduledFuture<*>? = null
		try {
			firstFuture = worker.startHeartbeat(firstClaim)
			assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue()
			secondFuture = worker.startHeartbeat(secondClaim)
			assertThat(secondRan.await(2, TimeUnit.SECONDS)).isTrue()
		} finally {
			releaseFirst.countDown()
			firstFuture?.cancel(true)
			secondFuture?.cancel(true)
			worker.shutdownExecutors()
		}
	}

	@Test
	fun `heartbeat 간격은 1초 미만을 허용하지 않는다`() {
		assertThatThrownBy {
			ImportRunWorker(
				Mockito.mock(ImportRunClaimService::class.java),
				Mockito.mock(ImportRunRepository::class.java),
				Mockito.mock(ExternalConnectionRepository::class.java),
				Mockito.mock(MailCollector::class.java),
				Mockito.mock(ImportRunCompletionService::class.java),
				1,
				Duration.ZERO,
				Duration.ofMillis(999),
				1,
			)
		}.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessage("jobvis.import.heartbeat-interval은 1초 이상 30초 이하여야 합니다.")
	}
}
