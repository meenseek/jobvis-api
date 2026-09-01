package com.meenseek.jobvis.home

import com.meenseek.jobvis.auth.CurrentUserProvider
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/home")
class HomeController(
	private val currentUserProvider: CurrentUserProvider,
	private val service: HomeService,
) {
	@GetMapping("/summary")
	fun summary(httpRequest: HttpServletRequest): HomeSummaryResponse =
		service.summary(currentUserProvider.currentUserId(httpRequest))
}
