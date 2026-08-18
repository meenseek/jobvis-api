package com.meenseek.jobvis.application

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "application_emails")
class ApplicationEmail private constructor(
	@field:Id
	val id: UUID,
	@field:Column(name = "user_id", nullable = false)
	val userId: UUID,
	@field:Column(name = "application_id", nullable = false)
	val applicationId: UUID,
	@field:Column(name = "connection_id", nullable = false)
	val connectionId: UUID,
	@field:Column(nullable = false, length = 20)
	val provider: String,
	@field:Column(name = "provider_message_id", nullable = false, length = 255)
	val providerMessageId: String,
	@field:Column(nullable = false, length = 500)
	val subject: String,
	@field:Column(nullable = false, length = 320)
	val sender: String,
	@field:Column(name = "received_at", nullable = false)
	val receivedAt: Instant,
	@field:Column(nullable = false, columnDefinition = "text")
	val summary: String,
	@field:Column(name = "created_at", nullable = false)
	val createdAt: Instant,
) {
	@field:Column(name = "recorded_order", nullable = false, insertable = false, updatable = false)
	val recordedOrder: Long = 0

	companion object {
		fun create(
			id: UUID,
			userId: UUID,
			applicationId: UUID,
			connectionId: UUID,
			provider: String,
			providerMessageId: String,
			subject: String,
			sender: String,
			receivedAt: Instant,
			summary: String,
			now: Instant,
		): ApplicationEmail = ApplicationEmail(
			id, userId, applicationId, connectionId, provider, providerMessageId,
			subject, sender, receivedAt, summary, now,
		)
	}
}
