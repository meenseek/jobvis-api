package com.meenseek.jobvis.connection

import com.meenseek.jobvis.auth.CurrentUserProvider
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/connections")
class ConnectionController(
	private val currentUserProvider: CurrentUserProvider,
	private val connectionService: ConnectionService,
	private val connectionOAuthService: ConnectionOAuthService,
) {
	@GetMapping("/capabilities")
	fun capabilities(httpRequest: HttpServletRequest): List<ConnectionCapabilityResponse> {
		currentUserProvider.currentUserId(httpRequest)
		return connectionService.capabilities()
	}

	@GetMapping
	fun list(
		@RequestParam(required = false) capability: String?,
		@RequestParam(required = false, defaultValue = "true") includeRevoked: Boolean,
		httpRequest: HttpServletRequest,
	): List<ExternalConnectionResponse> = connectionService.list(
		currentUserProvider.currentUserId(httpRequest), capability, includeRevoked,
	)

	@PostMapping("/naver")
	@ResponseStatus(HttpStatus.CREATED)
	fun connectNaver(
		@Valid @RequestBody request: ConnectNaverRequest,
		httpRequest: HttpServletRequest,
	): ExternalConnectionResponse =
		connectionService.connectNaver(currentUserProvider.currentUserId(httpRequest), request)

	@PostMapping("/{provider}/oauth/begin")
	fun beginOAuth(
		@PathVariable provider: String,
		@Valid @RequestBody request: BeginOAuthConnectionRequest,
		httpRequest: HttpServletRequest,
	): BeginOAuthConnectionResponse = connectionOAuthService.begin(
		currentUserProvider.currentUserId(httpRequest), provider, request,
	)

	@PostMapping("/{provider}/oauth/complete")
	@ResponseStatus(HttpStatus.CREATED)
	fun completeOAuth(
		@PathVariable provider: String,
		@Valid @RequestBody request: CompleteOAuthConnectionRequest,
		httpRequest: HttpServletRequest,
	): ExternalConnectionResponse = connectionOAuthService.complete(
		currentUserProvider.currentUserId(httpRequest), provider, request,
	)

	@PatchMapping("/{connectionId}/monitoring-consent")
	fun updateMonitoringConsent(
		@PathVariable connectionId: UUID,
		@Valid @RequestBody request: UpdateMonitoringConsentRequest,
		httpRequest: HttpServletRequest,
	): ExternalConnectionResponse = connectionService.updateMonitoringConsent(
		currentUserProvider.currentUserId(httpRequest), connectionId, request,
	)

	@PostMapping("/{connectionId}/monitoring/resume")
	fun resumeMonitoring(
		@PathVariable connectionId: UUID,
		@Valid @RequestBody request: ResumeMonitoringRequest,
		httpRequest: HttpServletRequest,
	): ExternalConnectionResponse = connectionService.resumeMonitoring(
		currentUserProvider.currentUserId(httpRequest), connectionId, request,
	)

	@DeleteMapping("/{connectionId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun revoke(@PathVariable connectionId: UUID, httpRequest: HttpServletRequest) {
		connectionService.revoke(currentUserProvider.currentUserId(httpRequest), connectionId)
	}
}
