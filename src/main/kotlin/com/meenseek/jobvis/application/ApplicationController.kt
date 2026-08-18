package com.meenseek.jobvis.application

import com.meenseek.jobvis.auth.CurrentUserProvider
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@Validated
@RequestMapping("/api/v1/applications")
class ApplicationController(
	private val currentUserProvider: CurrentUserProvider,
	private val applicationService: ApplicationService,
) {
	@GetMapping
	fun list(
		@RequestParam(required = false) q: String?,
		@RequestParam(required = false, defaultValue = "all") status: String?,
		httpRequest: HttpServletRequest,
	): ResponseEntity<List<ApplicationListItemResponse>> {
		val result = applicationService.list(currentUserProvider.currentUserId(httpRequest), q, status)
		return ResponseEntity.ok()
			.header("X-Jobvis-Limit", "200")
			.header("X-Jobvis-Has-Next", result.hasNext.toString())
			.body(result.items)
	}

	@GetMapping("/page")
	fun listPage(
		@RequestParam(required = false) q: String?,
		@RequestParam(required = false, defaultValue = "all") status: String?,
		@RequestParam(defaultValue = "0") page: Int,
		@RequestParam(defaultValue = "50") limit: Int,
		httpRequest: HttpServletRequest,
	): ApplicationListPageResponse = applicationService.listPage(
		currentUserProvider.currentUserId(httpRequest), q, status, page, limit,
	)

	@GetMapping("/{applicationId}")
	fun get(
		@PathVariable applicationId: UUID,
		httpRequest: HttpServletRequest,
	): ApplicationResponse =
		applicationService.get(currentUserProvider.currentUserId(httpRequest), applicationId)

	@GetMapping("/{applicationId}/schedule")
	fun getSchedule(
		@PathVariable applicationId: UUID,
		httpRequest: HttpServletRequest,
	): ApplicationScheduleResponse =
		applicationService.getSchedule(currentUserProvider.currentUserId(httpRequest), applicationId)

	@GetMapping("/{applicationId}/emails")
	fun emails(
		@PathVariable applicationId: UUID,
		@RequestParam(required = false) before: Long?,
		@RequestParam(defaultValue = "50") limit: Int,
		httpRequest: HttpServletRequest,
	): HistoryPageResponse<EmailResponse> = applicationService.emails(
		currentUserProvider.currentUserId(httpRequest), applicationId, before, limit,
	)

	@GetMapping("/{applicationId}/activities")
	fun activities(
		@PathVariable applicationId: UUID,
		@RequestParam(required = false) before: Long?,
		@RequestParam(defaultValue = "50") limit: Int,
		httpRequest: HttpServletRequest,
	): HistoryPageResponse<ActivityResponse> = applicationService.activities(
		currentUserProvider.currentUserId(httpRequest), applicationId, before, limit,
	)

	@GetMapping("/{applicationId}/changes")
	fun changes(
		@PathVariable applicationId: UUID,
		@RequestParam(required = false) before: Long?,
		@RequestParam(defaultValue = "50") limit: Int,
		httpRequest: HttpServletRequest,
	): HistoryPageResponse<ChangeResponse> = applicationService.changes(
		currentUserProvider.currentUserId(httpRequest), applicationId, before, limit,
	)

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(
		@Valid @RequestBody request: CreateRequest,
		httpRequest: HttpServletRequest,
	): ApplicationResponse =
		applicationService.create(currentUserProvider.currentUserId(httpRequest), request)

	@PatchMapping("/{applicationId}/details")
	fun updateDetails(
		@PathVariable applicationId: UUID,
		@Valid @RequestBody request: UpdateDetailsRequest,
		httpRequest: HttpServletRequest,
	): ApplicationResponse =
		applicationService.updateDetails(currentUserProvider.currentUserId(httpRequest), applicationId, request)

	@PutMapping("/{applicationId}/memo")
	fun updateMemo(
		@PathVariable applicationId: UUID,
		@Valid @RequestBody request: UpdateMemoRequest,
		httpRequest: HttpServletRequest,
	): ApplicationResponse =
		applicationService.updateMemo(currentUserProvider.currentUserId(httpRequest), applicationId, request)

	@PostMapping("/{applicationId}/status")
	fun updateStatus(
		@PathVariable applicationId: UUID,
		@Valid @RequestBody request: UpdateStatusRequest,
		httpRequest: HttpServletRequest,
	): ApplicationResponse =
		applicationService.updateStatus(currentUserProvider.currentUserId(httpRequest), applicationId, request)

	@PostMapping("/{applicationId}/schedule/complete")
	fun completeSchedule(
		@PathVariable applicationId: UUID,
		@Valid @RequestBody request: MutationRequest,
		httpRequest: HttpServletRequest,
	): ApplicationResponse =
		applicationService.completeSchedule(currentUserProvider.currentUserId(httpRequest), applicationId, request)

	@PutMapping("/{applicationId}/schedule")
	fun updateSchedule(
		@PathVariable applicationId: UUID,
		@Valid @RequestBody request: UpdateScheduleRequest,
		httpRequest: HttpServletRequest,
	): ApplicationScheduleResponse =
		applicationService.updateSchedule(currentUserProvider.currentUserId(httpRequest), applicationId, request)

	@PostMapping("/{applicationId}/review/complete")
	fun completeReview(
		@PathVariable applicationId: UUID,
		@Valid @RequestBody request: MutationRequest,
		httpRequest: HttpServletRequest,
	): ApplicationResponse =
		applicationService.completeReview(currentUserProvider.currentUserId(httpRequest), applicationId, request)
}
