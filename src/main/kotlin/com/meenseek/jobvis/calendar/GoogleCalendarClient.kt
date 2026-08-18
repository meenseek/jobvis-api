package com.meenseek.jobvis.calendar

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.ObjectMapper

data class GoogleCalendarEvent(
	val providerEventId: String,
	val title: String,
	val startsAt: String,
	val endsAt: String,
	val timezone: String,
	val location: String,
	val description: String,
)

class CalendarProviderException(
	val errorCode: String,
	val reauthorizationRequired: Boolean = false,
	cause: Throwable? = null,
) : RuntimeException(errorCode, cause)

fun interface GoogleCalendarClient {
	fun insert(accessToken: String, event: GoogleCalendarEvent): String
}

@Component
class OfficialGoogleCalendarClient(
	private val objectMapper: ObjectMapper,
	@Qualifier("externalRestClient") private val restClient: RestClient,
) : GoogleCalendarClient {
	override fun insert(accessToken: String, event: GoogleCalendarEvent): String {
		val body = mapOf(
			"id" to event.providerEventId,
			"summary" to event.title,
			"description" to event.description,
			"location" to event.location,
			"start" to mapOf("dateTime" to event.startsAt, "timeZone" to event.timezone),
			"end" to mapOf("dateTime" to event.endsAt, "timeZone" to event.timezone),
		)
		return try {
			val response = restClient.post()
				.uri("https://www.googleapis.com/calendar/v3/calendars/primary/events")
				.header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
				.body(body)
				.retrieve()
				.body(String::class.java)
				?.let(objectMapper::readTree)
			val providerId = response?.path("id")?.asString().orEmpty()
			providerId.takeIf(String::isNotBlank)
				?: throw CalendarProviderException("GOOGLE_CALENDAR_EMPTY_RESPONSE")
		} catch (exception: RestClientResponseException) {
			if (exception.statusCode == HttpStatus.CONFLICT) event.providerEventId
			else throw CalendarProviderException(
				"GOOGLE_CALENDAR_WRITE_FAILED",
				exception.statusCode.value() == 401 || isAuthorizationFailure(exception),
				exception,
			)
		} catch (exception: CalendarProviderException) {
			throw exception
		} catch (exception: RestClientException) {
			throw CalendarProviderException("GOOGLE_CALENDAR_WRITE_FAILED", cause = exception)
		} catch (exception: Exception) {
			throw CalendarProviderException("GOOGLE_CALENDAR_WRITE_FAILED", cause = exception)
		}
	}

	private fun isAuthorizationFailure(exception: RestClientResponseException): Boolean {
		if (exception.statusCode.value() != 403) return false
		val reasons: Set<String> = runCatching {
			buildSet {
				objectMapper.readTree(exception.responseBodyAsString).path("error").path("errors")
					.forEach { node -> add(node.path("reason").asString()) }
			}
		}.getOrDefault(emptySet())
		return reasons.any { it in AUTHORIZATION_REASONS }
	}

	private companion object {
		val AUTHORIZATION_REASONS = setOf("authError", "insufficientPermissions")
	}
}
