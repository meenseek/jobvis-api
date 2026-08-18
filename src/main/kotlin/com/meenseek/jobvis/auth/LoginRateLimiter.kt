package com.meenseek.jobvis.auth

import com.meenseek.jobvis.common.TooManyRequestsException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap

@Component
class LoginRateLimiter(
	private val clock: Clock,
	@Value("\${jobvis.auth.rate-limit-window:PT10M}") private val window: Duration,
	@Value("\${jobvis.auth.rate-limit-per-ip:60}") private val maxRequests: Int,
	@Value("\${jobvis.auth.rate-limit-max-clients:20000}") private val maxTrackedClients: Int,
) {
	private val windows = LinkedHashMap<String, RequestWindow>()
	private val monitor = Any()
	private var nextCleanupAt: Instant = Instant.EPOCH

	init {
		require(!window.isZero && !window.isNegative) { "jobvis.auth.rate-limit-window은 양수여야 합니다." }
		require(maxRequests in 1..10_000) { "jobvis.auth.rate-limit-per-ip는 1~10000이어야 합니다." }
		require(maxTrackedClients in 1..100_000) { "jobvis.auth.rate-limit-max-clients는 1~100000이어야 합니다." }
	}

	fun check(clientAddress: String, operation: String) {
		val now = Instant.now(clock)
		val key = "$operation:${clientAddress.take(200)}"
		synchronized(monitor) {
			cleanupExpired(now)
			val current = windows[key]
			if (current != null && current.expiresAt.isAfter(now)) {
				if (current.count >= maxRequests) tooManyRequests()
				windows[key] = current.copy(count = current.count + 1)
				return
			}
			windows.remove(key)
			if (windows.size >= maxTrackedClients) tooManyRequests()
			windows[key] = RequestWindow(1, now.plus(window))
		}
	}

	private fun cleanupExpired(now: Instant) {
		if (nextCleanupAt.isAfter(now)) return
		val iterator = windows.entries.iterator()
		while (iterator.hasNext()) {
			if (!iterator.next().value.expiresAt.isAfter(now)) iterator.remove()
		}
		nextCleanupAt = now.plusSeconds(1)
	}

	private fun tooManyRequests(): Nothing =
		throw TooManyRequestsException("로그인 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")

	private data class RequestWindow(val count: Int, val expiresAt: Instant)
}
