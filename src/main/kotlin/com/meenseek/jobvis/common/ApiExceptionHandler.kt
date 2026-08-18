package com.meenseek.jobvis.common

import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(ApiException::class)
	fun handleApiException(exception: ApiException): ProblemDetail =
		problem(exception.status, exception.message ?: "요청을 처리할 수 없습니다.")

	@ExceptionHandler(
		MethodArgumentNotValidException::class,
		HandlerMethodValidationException::class,
		ConstraintViolationException::class,
	)
	fun handleValidation(): ProblemDetail =
		problem(HttpStatus.BAD_REQUEST, "요청 값을 확인해 주세요.")

	@ExceptionHandler(
		ObjectOptimisticLockingFailureException::class,
		DataIntegrityViolationException::class,
	)
	fun handleConflict(): ProblemDetail =
		problem(HttpStatus.CONFLICT, "다른 변경이 먼저 저장되었습니다. 최신 내용을 확인한 뒤 다시 시도해 주세요.")

	private fun problem(status: HttpStatus, detail: String): ProblemDetail =
		ProblemDetail.forStatusAndDetail(status, detail).apply {
			title = status.reasonPhrase
		}
}
