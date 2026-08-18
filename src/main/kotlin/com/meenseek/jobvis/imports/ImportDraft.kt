package com.meenseek.jobvis.imports

import com.meenseek.jobvis.application.ApplicationResult
import com.meenseek.jobvis.application.ApplicationStage
import com.meenseek.jobvis.application.ScheduleType
import com.meenseek.jobvis.connection.ConnectionProvider
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "import_drafts")
class ImportDraft private constructor(
	id: UUID,
	userId: UUID,
	runId: UUID,
	connectionId: UUID,
	provider: ConnectionProvider,
	providerMessageId: String,
	subject: String,
	sender: String,
	receivedAt: Instant,
	sourceSummary: String,
	company: String,
	position: String,
	location: String,
	employmentType: String,
	appliedAt: LocalDate,
	stage: ApplicationStage,
	highestStageReached: ApplicationStage,
	screeningPassed: Boolean,
	result: ApplicationResult,
	scheduleType: ScheduleType?,
	scheduleAction: String?,
	scheduledAt: Instant?,
	scheduleEndsAt: Instant?,
	confidence: BigDecimal,
	status: ImportDraftStatus,
	acceptedApplicationId: UUID?,
	decisionMutationId: UUID?,
	decisionFingerprint: String?,
	version: Long,
	decidedAt: Instant?,
	purgeAfter: Instant,
	createdAt: Instant,
	updatedAt: Instant,
) {
	@field:Id
	@field:Column(name = "id", nullable = false)
	private var storedId: UUID = id
	@field:Column(name = "user_id", nullable = false)
	private var storedUserId: UUID = userId
	@field:Column(name = "run_id", nullable = false)
	private var storedRunId: UUID = runId
	@field:Column(name = "connection_id", nullable = false)
	private var storedConnectionId: UUID = connectionId
	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "provider", nullable = false, length = 30)
	private var storedProvider: ConnectionProvider = provider
	@field:Column(name = "provider_message_id", nullable = false, length = 255)
	private var storedProviderMessageId: String = providerMessageId
	@field:Column(name = "subject", nullable = false, length = 500)
	private var storedSubject: String = subject
	@field:Column(name = "sender", nullable = false, length = 320)
	private var storedSender: String = sender
	@field:Column(name = "received_at", nullable = false)
	private var storedReceivedAt: Instant = receivedAt
	@field:Column(name = "source_summary", nullable = false, length = 1000)
	private var storedSourceSummary: String = sourceSummary
	@field:Column(name = "company", nullable = false, length = 160)
	private var storedCompany: String = company
	@field:Column(name = "position", nullable = false, length = 160)
	private var storedPosition: String = position
	@field:Column(name = "location", nullable = false, length = 160)
	private var storedLocation: String = location
	@field:Column(name = "employment_type", nullable = false, length = 80)
	private var storedEmploymentType: String = employmentType
	@field:Column(name = "applied_at", nullable = false)
	private var storedAppliedAt: LocalDate = appliedAt
	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "stage", nullable = false, length = 20)
	private var storedStage: ApplicationStage = stage
	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "highest_stage_reached", nullable = false, length = 20)
	private var storedHighestStageReached: ApplicationStage = highestStageReached
	@field:Column(name = "screening_passed", nullable = false)
	private var storedScreeningPassed: Boolean = screeningPassed
	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "result", nullable = false, length = 20)
	private var storedResult: ApplicationResult = result
	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "schedule_type", length = 20)
	private var storedScheduleType: ScheduleType? = scheduleType
	@field:Column(name = "schedule_action", length = 200)
	private var storedScheduleAction: String? = scheduleAction
	@field:Column(name = "scheduled_at")
	private var storedScheduledAt: Instant? = scheduledAt
	@field:Column(name = "schedule_ends_at")
	private var storedScheduleEndsAt: Instant? = scheduleEndsAt
	@field:Column(name = "confidence", nullable = false, precision = 4, scale = 3)
	private var storedConfidence: BigDecimal = confidence
	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "status", nullable = false, length = 20)
	private var storedStatus: ImportDraftStatus = status
	@field:Column(name = "accepted_application_id")
	private var storedAcceptedApplicationId: UUID? = acceptedApplicationId
	@field:Column(name = "decision_mutation_id")
	private var storedDecisionMutationId: UUID? = decisionMutationId
	@field:Column(name = "decision_fingerprint", length = 64)
	private var storedDecisionFingerprint: String? = decisionFingerprint
	@field:Version
	@field:Column(name = "version", nullable = false)
	private var storedVersion: Long = version
	@field:Column(name = "decided_at")
	private var storedDecidedAt: Instant? = decidedAt
	@field:Column(name = "purge_after", nullable = false)
	private var storedPurgeAfter: Instant = purgeAfter
	@field:Column(name = "created_at", nullable = false)
	private var storedCreatedAt: Instant = createdAt
	@field:Column(name = "updated_at", nullable = false)
	private var storedUpdatedAt: Instant = updatedAt

	val id: UUID get() = storedId
	val userId: UUID get() = storedUserId
	val runId: UUID get() = storedRunId
	val connectionId: UUID get() = storedConnectionId
	val provider: ConnectionProvider get() = storedProvider
	val providerMessageId: String get() = storedProviderMessageId
	val subject: String get() = storedSubject
	val sender: String get() = storedSender
	val receivedAt: Instant get() = storedReceivedAt
	val sourceSummary: String get() = storedSourceSummary
	val company: String get() = storedCompany
	val position: String get() = storedPosition
	val location: String get() = storedLocation
	val employmentType: String get() = storedEmploymentType
	val appliedAt: LocalDate get() = storedAppliedAt
	val stage: ApplicationStage get() = storedStage
	val highestStageReached: ApplicationStage get() = storedHighestStageReached
	val screeningPassed: Boolean get() = storedScreeningPassed
	val result: ApplicationResult get() = storedResult
	val scheduleType: ScheduleType? get() = storedScheduleType
	val scheduleAction: String? get() = storedScheduleAction
	val scheduledAt: Instant? get() = storedScheduledAt
	val scheduleEndsAt: Instant? get() = storedScheduleEndsAt
	val confidence: BigDecimal get() = storedConfidence
	val status: ImportDraftStatus get() = storedStatus
	val acceptedApplicationId: UUID? get() = storedAcceptedApplicationId
	val decisionMutationId: UUID? get() = storedDecisionMutationId
	val decisionFingerprint: String? get() = storedDecisionFingerprint
	val version: Long get() = storedVersion
	val decidedAt: Instant? get() = storedDecidedAt
	val purgeAfter: Instant get() = storedPurgeAfter
	val createdAt: Instant get() = storedCreatedAt

	fun update(
		company: String,
		position: String,
		location: String,
		employmentType: String,
		appliedAt: LocalDate,
		stage: ApplicationStage,
		highestStageReached: ApplicationStage,
		screeningPassed: Boolean,
		result: ApplicationResult,
		scheduleType: ScheduleType?,
		scheduleAction: String?,
		scheduledAt: Instant?,
		scheduleEndsAt: Instant?,
		now: Instant,
	) {
		require(storedStatus == ImportDraftStatus.PENDING) { "대기 중인 초안만 수정할 수 있습니다." }
		storedCompany = company
		storedPosition = position
		storedLocation = location
		storedEmploymentType = employmentType
		storedAppliedAt = appliedAt
		storedStage = stage
		storedHighestStageReached = highestStageReached
		storedScreeningPassed = screeningPassed
		storedResult = result
		storedScheduleType = scheduleType
		storedScheduleAction = scheduleAction
		storedScheduledAt = scheduledAt
		storedScheduleEndsAt = scheduleEndsAt
		storedUpdatedAt = now
	}

	fun accept(applicationId: UUID, mutationId: UUID, decisionFingerprint: String, now: Instant) {
		require(storedStatus == ImportDraftStatus.PENDING) { "대기 중인 초안만 수락할 수 있습니다." }
		storedStatus = ImportDraftStatus.ACCEPTED
		storedAcceptedApplicationId = applicationId
		storedDecisionMutationId = mutationId
		storedDecisionFingerprint = decisionFingerprint
		storedDecidedAt = now
		storedUpdatedAt = now
	}

	fun reject(mutationId: UUID, decisionFingerprint: String, now: Instant) {
		require(storedStatus == ImportDraftStatus.PENDING) { "대기 중인 초안만 제외할 수 있습니다." }
		storedStatus = ImportDraftStatus.REJECTED
		storedDecisionMutationId = mutationId
		storedDecisionFingerprint = decisionFingerprint
		storedDecidedAt = now
		storedUpdatedAt = now
	}

	companion object {
		fun pending(
			id: UUID,
			userId: UUID,
			runId: UUID,
			connectionId: UUID,
			candidate: MailCandidate,
			analysis: AnalyzedMailCandidate,
			now: Instant,
			purgeAfter: Instant,
		): ImportDraft = ImportDraft(
			id, userId, runId, connectionId, candidate.provider, candidate.providerMessageId.take(255),
			candidate.subject.take(500), candidate.sender.take(320), candidate.receivedAt,
			analysis.sourceSummary.take(1000), analysis.company, analysis.position,
			analysis.location, analysis.employmentType, analysis.appliedAt, analysis.stage,
			analysis.highestStageReached, analysis.screeningPassed, analysis.result,
			analysis.scheduleType, analysis.scheduleAction, analysis.scheduledAt, analysis.scheduleEndsAt,
			analysis.confidence, ImportDraftStatus.PENDING, null, null, null, 0, null,
			purgeAfter, now, now,
		)
	}
}
