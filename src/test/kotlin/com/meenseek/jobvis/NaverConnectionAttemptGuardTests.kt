package com.meenseek.jobvis

import com.meenseek.jobvis.common.TooManyRequestsException
import com.meenseek.jobvis.connection.NaverConnectionAttemptGuard
import com.meenseek.jobvis.connection.NaverValidationAttemptStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NaverConnectionAttemptGuardTests {
	@Test
	fun `같은 사용자와 계정의 네이버 검증 시도 횟수를 제한한다`() {
		val guard = guard(maxAttempts = 2, maxConcurrent = 2)
		val userId = UUID.randomUUID()
		repeat(2) { guard.execute(userId, "same@naver.com") { } }

		assertThatThrownBy { guard.execute(userId, "same@naver.com") { } }
			.isInstanceOf(TooManyRequestsException::class.java)
	}

	@Test
	fun `같은 사용자는 이메일을 바꿔도 네이버 검증 제한을 우회할 수 없다`() {
		val guard = guard(maxAttempts = 2, maxConcurrent = 2)
		val userId = UUID.randomUUID()
		guard.execute(userId, "first@naver.com") { }
		guard.execute(userId, "second@naver.com") { }

		assertThatThrownBy { guard.execute(userId, "third@naver.com") { } }
			.isInstanceOf(TooManyRequestsException::class.java)
	}

	@Test
	fun `여러 인스턴스와 사용자가 같은 네이버 계정 제한을 공유한다`() {
		val store = TestAttemptStore()
		val firstInstance = guard(maxAttempts = 2, maxConcurrent = 2, store = store)
		val secondInstance = guard(maxAttempts = 2, maxConcurrent = 2, store = store)
		firstInstance.execute(UUID.randomUUID(), "shared@naver.com") { }
		firstInstance.execute(UUID.randomUUID(), "shared@naver.com") { }

		assertThatThrownBy { secondInstance.execute(UUID.randomUUID(), "shared@naver.com") { } }
			.isInstanceOf(TooManyRequestsException::class.java)
	}

	@Test
	fun `네이버 외부 검증의 프로세스 전체 동시 실행 수를 제한한다`() {
		val guard = guard(maxAttempts = 10, maxConcurrent = 1)
		val started = CountDownLatch(1)
		val release = CountDownLatch(1)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val running = executor.submit {
				guard.execute(UUID.randomUUID(), "first@naver.com") {
					started.countDown()
					check(release.await(10, TimeUnit.SECONDS))
				}
			}
			assertThat(started.await(10, TimeUnit.SECONDS)).isTrue()
			assertThatThrownBy { guard.execute(UUID.randomUUID(), "second@naver.com") { } }
				.isInstanceOf(TooManyRequestsException::class.java)
			release.countDown()
			running.get(10, TimeUnit.SECONDS)
		} finally {
			release.countDown()
			executor.shutdownNow()
		}
	}

	private fun guard(
		maxAttempts: Int,
		maxConcurrent: Int,
		store: NaverValidationAttemptStore = TestAttemptStore(),
	): NaverConnectionAttemptGuard =
		NaverConnectionAttemptGuard(
			store,
			Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC),
			Duration.ofMinutes(10), maxAttempts, 100, maxConcurrent,
		)

	private class TestAttemptStore : NaverValidationAttemptStore {
		private val attempts = mutableMapOf<String, Window>()

		@Synchronized
		override fun record(
			userId: UUID,
			accountEmail: String,
			now: Instant,
			window: Duration,
			maxAttempts: Int,
			maxClients: Int,
		): Boolean {
			attempts.entries.removeIf { !it.value.expiresAt.isAfter(now) }
			val keys = listOf("user:$userId", "account:${accountEmail.trim().lowercase()}")
			if (keys.any { (attempts[it]?.count ?: 0) >= maxAttempts }) return false
			if (keys.count { it !in attempts } + attempts.size > maxClients * 2) return false
			keys.forEach { key ->
				val current = attempts[key]
				attempts[key] = current?.copy(count = current.count + 1) ?: Window(1, now.plus(window))
			}
			return true
		}

		private data class Window(val count: Int, val expiresAt: Instant)
	}
}
