package com.meenseek.jobvis.calendar

import com.meenseek.jobvis.auth.CurrentUserProvider
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/calendar-exports")
class CalendarExportController(
	private val currentUserProvider: CurrentUserProvider,
	private val service: CalendarExportService,
) {
	@PostMapping("/previews")
	@ResponseStatus(HttpStatus.CREATED)
	fun preview(
		@Valid @RequestBody request: CreateCalendarPreviewRequest,
		httpRequest: HttpServletRequest,
	): CalendarExportResponse = service.preview(currentUserProvider.currentUserId(httpRequest), request)

	@GetMapping("/{exportId}")
	fun get(@PathVariable exportId: UUID, httpRequest: HttpServletRequest): CalendarExportResponse =
		service.get(currentUserProvider.currentUserId(httpRequest), exportId)

	@PostMapping("/{exportId}/confirm")
	fun confirm(
		@PathVariable exportId: UUID,
		@Valid @RequestBody request: ConfirmCalendarExportRequest,
		httpRequest: HttpServletRequest,
	): CalendarExportResponse = service.confirm(currentUserProvider.currentUserId(httpRequest), exportId, request)
}
