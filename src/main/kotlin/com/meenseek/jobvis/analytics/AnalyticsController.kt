package com.meenseek.jobvis.analytics

import com.meenseek.jobvis.auth.CurrentUserProvider
import jakarta.servlet.http.HttpServletRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/analytics")
class AnalyticsController(
	private val currentUserProvider: CurrentUserProvider,
	private val service: AnalyticsService,
) {
	@GetMapping("/summary")
	fun summary(
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		from: LocalDate?,
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		to: LocalDate?,
		httpRequest: HttpServletRequest,
	): ApplicationAnalyticsResponse =
		service.summary(currentUserProvider.currentUserId(httpRequest), from, to)
}
