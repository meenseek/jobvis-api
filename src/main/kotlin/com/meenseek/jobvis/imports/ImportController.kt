package com.meenseek.jobvis.imports

import com.meenseek.jobvis.application.ApplicationResponse
import com.meenseek.jobvis.auth.CurrentUserProvider
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
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
@RequestMapping("/api/v1/import-runs")
class ImportRunController(
	private val currentUserProvider: CurrentUserProvider,
	private val service: ImportRunService,
) {
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun create(
		@Valid @RequestBody request: CreateImportRunRequest,
		httpRequest: HttpServletRequest,
	): ImportRunResponse = service.create(currentUserProvider.currentUserId(httpRequest), request)

	@GetMapping
	fun list(
		@RequestParam(required = false, defaultValue = "0") page: Int,
		@RequestParam(required = false, defaultValue = "50") size: Int,
		httpRequest: HttpServletRequest,
	): ImportPageResponse<ImportRunResponse> =
		service.list(currentUserProvider.currentUserId(httpRequest), page, size)

	@GetMapping("/{runId}")
	fun get(@PathVariable runId: UUID, httpRequest: HttpServletRequest): ImportRunResponse =
		service.get(currentUserProvider.currentUserId(httpRequest), runId)

	@PostMapping("/{runId}/cancel")
	fun cancel(@PathVariable runId: UUID, httpRequest: HttpServletRequest): ImportRunResponse =
		service.cancel(currentUserProvider.currentUserId(httpRequest), runId)
}

@RestController
@RequestMapping("/api/v1/import-drafts")
class ImportDraftController(
	private val currentUserProvider: CurrentUserProvider,
	private val service: ImportDraftService,
) {
	@GetMapping
	fun list(
		@RequestParam(required = false) status: String?,
		@RequestParam(required = false, defaultValue = "0") page: Int,
		@RequestParam(required = false, defaultValue = "50") size: Int,
		httpRequest: HttpServletRequest,
	): ImportPageResponse<ImportDraftResponse> =
		service.list(currentUserProvider.currentUserId(httpRequest), status, page, size)

	@GetMapping("/{draftId}")
	fun get(@PathVariable draftId: UUID, httpRequest: HttpServletRequest): ImportDraftResponse =
		service.get(currentUserProvider.currentUserId(httpRequest), draftId)

	@PatchMapping("/{draftId}")
	fun update(
		@PathVariable draftId: UUID,
		@Valid @RequestBody request: UpdateImportDraftRequest,
		httpRequest: HttpServletRequest,
	): ImportDraftResponse = service.update(currentUserProvider.currentUserId(httpRequest), draftId, request)

	@PostMapping("/{draftId}/accept")
	fun accept(
		@PathVariable draftId: UUID,
		@Valid @RequestBody request: AcceptImportDraftRequest,
		httpRequest: HttpServletRequest,
	): ApplicationResponse = service.accept(currentUserProvider.currentUserId(httpRequest), draftId, request)

	@PostMapping("/{draftId}/reject")
	fun reject(
		@PathVariable draftId: UUID,
		@Valid @RequestBody request: DecideImportDraftRequest,
		httpRequest: HttpServletRequest,
	): ImportDraftResponse = service.reject(currentUserProvider.currentUserId(httpRequest), draftId, request)
}
