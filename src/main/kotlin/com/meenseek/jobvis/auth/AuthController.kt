package com.meenseek.jobvis.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
	private val authService: AuthService,
	private val currentUserProvider: CurrentUserProvider,
	private val loginRateLimiter: LoginRateLimiter,
) {
	@GetMapping("/providers")
	fun providers(): List<LoginProviderResponse> = authService.providers()

	@PostMapping("/challenges")
	@ResponseStatus(HttpStatus.CREATED)
	fun createChallenge(
		@Valid @RequestBody request: CreateLoginChallengeRequest,
		httpRequest: HttpServletRequest,
	): LoginChallengeResponse {
		loginRateLimiter.check(httpRequest.remoteAddr.orEmpty(), "challenge")
		return authService.createChallenge(request)
	}

	@PostMapping("/exchange")
	fun exchange(
		@Valid @RequestBody request: ExchangeIdentityTokenRequest,
		httpRequest: HttpServletRequest,
	): AuthSessionResponse {
		loginRateLimiter.check(httpRequest.remoteAddr.orEmpty(), "exchange")
		return authService.exchange(request)
	}

	@GetMapping("/me")
	fun me(httpRequest: HttpServletRequest): AuthUserResponse =
		authService.me(currentUserProvider.currentUserId(httpRequest))

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun logout(httpRequest: HttpServletRequest) {
		authService.logout(httpRequest)
	}
}
