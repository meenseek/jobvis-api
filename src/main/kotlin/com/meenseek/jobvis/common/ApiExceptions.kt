package com.meenseek.jobvis.common

import org.springframework.http.HttpStatus

open class ApiException(
	val status: HttpStatus,
	message: String,
) : RuntimeException(message)

class BadRequestException(message: String) : ApiException(HttpStatus.BAD_REQUEST, message)

class ConflictException(message: String) : ApiException(HttpStatus.CONFLICT, message)

class NotFoundException(message: String) : ApiException(HttpStatus.NOT_FOUND, message)

class UnauthorizedException(message: String) : ApiException(HttpStatus.UNAUTHORIZED, message)

class ForbiddenException(message: String) : ApiException(HttpStatus.FORBIDDEN, message)

class ServiceUnavailableException(message: String) : ApiException(HttpStatus.SERVICE_UNAVAILABLE, message)

class ExternalConnectionAuthorizationException(
	message: String,
	val connectionVersion: Long? = null,
) : ApiException(HttpStatus.FAILED_DEPENDENCY, message)

class TooManyRequestsException(message: String) : ApiException(HttpStatus.TOO_MANY_REQUESTS, message)
