package com.meenseek.jobvis.imports

import com.meenseek.jobvis.common.BadRequestException
import com.meenseek.jobvis.connection.ExternalConnectionRepository
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Component
class MonitoringImportScheduler(
	private val connectionRepository: ExternalConnectionRepository,
	private val runService: ImportRunService,
	private val clock: Clock,
) {
	@Scheduled(fixedDelayString = "\${jobvis.import.monitor-delay:PT1M}")
	fun enqueueDue(): Int {
		val now = Instant.now(clock)
		val today = LocalDate.ofInstant(now, SEOUL)
		var queued = 0
		connectionRepository.findDueForSync(now, PageRequest.of(0, MAX_CONNECTIONS_PER_POLL)).forEach { connection ->
			try {
				val from = connection.lastSyncedAt
					?.let { LocalDate.ofInstant(it, SEOUL).minusDays(OVERLAP_DAYS) }
					?: today.minusDays(INITIAL_LOOKBACK_DAYS)
				val to = minOf(today, from.plusYears(MAX_RANGE_YEARS))
				if (runService.queueMonitor(connection.userId, connection.id, from, to) != null) queued++
			} catch (exception: BadRequestException) {
				logger.warn(
					"자동 메일 확인 범위가 올바르지 않아 연결을 건너뜁니다. connectionId={}, reason={}",
					connection.id,
					exception.message,
				)
			}
		}
		return queued
	}

	private companion object {
		val logger = LoggerFactory.getLogger(MonitoringImportScheduler::class.java)
		val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
		const val MAX_CONNECTIONS_PER_POLL = 100
		const val INITIAL_LOOKBACK_DAYS = 7L
		const val OVERLAP_DAYS = 1L
		const val MAX_RANGE_YEARS = 10L
	}
}
