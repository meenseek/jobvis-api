package com.meenseek.jobvis.application

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "applications")
class JobApplication private constructor(
	id: UUID,
	userId: UUID,
	company: String,
	position: String,
	location: String,
	employmentType: String,
	appliedAt: LocalDate,
	stage: ApplicationStage,
	highestStageReached: ApplicationStage,
	screeningPassed: Boolean,
	result: ApplicationResult,
	needsReview: Boolean,
	source: String,
	sourceType: ApplicationSourceType,
	memo: String,
	creationMutationId: UUID,
	lastMutationId: UUID,
	version: Long,
	createdAt: Instant,
	updatedAt: Instant,
) {
	@field:Id
	@field:Column(name = "id", nullable = false)
	private var storedId: UUID = id

	@field:Column(name = "user_id", nullable = false)
	private var storedUserId: UUID = userId

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

	@field:Column(name = "needs_review", nullable = false)
	private var storedNeedsReview: Boolean = needsReview

	@field:Column(name = "source", nullable = false, length = 80)
	private var storedSource: String = source

	@field:Enumerated(EnumType.STRING)
	@field:Column(name = "source_type", nullable = false, length = 20)
	private var storedSourceType: ApplicationSourceType = sourceType

	@field:Column(name = "memo", nullable = false, columnDefinition = "text")
	private var storedMemo: String = memo

	@field:Column(name = "creation_mutation_id", nullable = false)
	private var storedCreationMutationId: UUID = creationMutationId

	@field:Column(name = "last_mutation_id", nullable = false)
	private var storedLastMutationId: UUID = lastMutationId

	@field:Version
	@field:Column(name = "version", nullable = false)
	private var storedVersion: Long = version

	@field:Column(name = "created_at", nullable = false)
	private var storedCreatedAt: Instant = createdAt

	@field:Column(name = "updated_at", nullable = false)
	private var storedUpdatedAt: Instant = updatedAt

	val id: UUID get() = storedId
	val company: String get() = storedCompany
	val position: String get() = storedPosition
	val location: String get() = storedLocation
	val employmentType: String get() = storedEmploymentType
	val appliedAt: LocalDate get() = storedAppliedAt
	val stage: ApplicationStage get() = storedStage
	val highestStageReached: ApplicationStage get() = storedHighestStageReached
	val screeningPassed: Boolean get() = storedScreeningPassed
	val result: ApplicationResult get() = storedResult
	val needsReview: Boolean get() = storedNeedsReview
	val source: String get() = storedSource
	val sourceType: ApplicationSourceType get() = storedSourceType
	val memo: String get() = storedMemo
	val version: Long get() = storedVersion

	fun updateDetails(
		company: String,
		position: String,
		location: String,
		employmentType: String,
		appliedAt: LocalDate,
		now: Instant,
	) {
		storedCompany = company
		storedPosition = position
		storedLocation = location
		storedEmploymentType = employmentType
		storedAppliedAt = appliedAt
		touch(now)
	}

	fun updateMemo(memo: String, now: Instant) {
		storedMemo = memo
		touch(now)
	}

	fun completeReview(now: Instant) {
		storedNeedsReview = false
		touch(now)
	}

	fun transitionToStage(nextStage: ApplicationStage, now: Instant) {
		storedStage = nextStage
		storedHighestStageReached = storedHighestStageReached.highest(nextStage)
		storedScreeningPassed = storedScreeningPassed || nextStage.passedScreeningByProgress()
		storedResult = ApplicationResult.ACTIVE
		touch(now)
	}

	fun transitionToOffered(now: Instant) {
		storedStage = ApplicationStage.OFFER
		storedHighestStageReached = ApplicationStage.OFFER
		storedScreeningPassed = true
		storedResult = ApplicationResult.OFFERED
		touch(now)
	}

	fun transitionToRejected(now: Instant) {
		storedResult = ApplicationResult.REJECTED
		touch(now)
	}

	fun applyImportedProgress(
		stage: ApplicationStage,
		highestStageReached: ApplicationStage,
		screeningPassed: Boolean,
		result: ApplicationResult,
		needsReview: Boolean,
		now: Instant,
	): Boolean {
		val mergedStage = storedStage.highest(stage)
		val mergedHighestStage = storedHighestStageReached.highest(highestStageReached).highest(stage)
		val mergedScreeningPassed = storedScreeningPassed || screeningPassed ||
			mergedHighestStage.passedScreeningByProgress()
		val mergedResult = when {
			storedResult == ApplicationResult.OFFERED -> ApplicationResult.OFFERED
			result == ApplicationResult.OFFERED -> ApplicationResult.OFFERED
			storedResult == ApplicationResult.REJECTED && result == ApplicationResult.ACTIVE -> ApplicationResult.REJECTED
			else -> result
		}
		val progressAdvanced = mergedStage != storedStage ||
			mergedHighestStage != storedHighestStageReached ||
			mergedScreeningPassed != storedScreeningPassed ||
			mergedResult != storedResult
		val mergedNeedsReview = storedNeedsReview || (needsReview && progressAdvanced)
		val changed = progressAdvanced || mergedNeedsReview != storedNeedsReview
		if (!changed) return false
		storedStage = mergedStage
		storedHighestStageReached = mergedHighestStage
		storedScreeningPassed = mergedScreeningPassed
		storedResult = mergedResult
		storedNeedsReview = mergedNeedsReview
		touch(now)
		return true
	}

	fun recordImportedMessage(mutationId: UUID, now: Instant): Boolean {
		val reviewMembershipAdded = !storedNeedsReview
		storedNeedsReview = true
		storedLastMutationId = mutationId
		touch(now)
		return reviewMembershipAdded
	}

	fun markMutation(mutationId: UUID, now: Instant) {
		storedLastMutationId = mutationId
		storedUpdatedAt = now
	}

	fun currentStatusValue(): String = when (storedResult) {
		ApplicationResult.OFFERED -> "offered"
		ApplicationResult.REJECTED -> "rejected"
		ApplicationResult.ACTIVE -> storedStage.apiValue()
	}

	fun currentStatusLabel(): String = when (storedResult) {
		ApplicationResult.OFFERED -> "최종 합격"
		ApplicationResult.REJECTED -> "전형 종료"
		ApplicationResult.ACTIVE -> storedStage.label
	}

	private fun touch(now: Instant) {
		storedUpdatedAt = now
	}

	companion object {
		fun create(
			id: UUID,
			userId: UUID,
			company: String,
			position: String,
			stage: ApplicationStage,
			creationMutationId: UUID,
			appliedAt: LocalDate,
			now: Instant,
		): JobApplication = JobApplication(
			id = id,
			userId = userId,
			company = company,
			position = position,
			location = "근무지 미입력",
			employmentType = "고용 형태 미입력",
			appliedAt = appliedAt,
			stage = stage,
			highestStageReached = stage,
			screeningPassed = stage.passedScreeningByProgress(),
			result = ApplicationResult.ACTIVE,
			needsReview = false,
			source = "직접 추가",
			sourceType = ApplicationSourceType.MANUAL,
			memo = "",
			creationMutationId = creationMutationId,
			lastMutationId = creationMutationId,
			version = 0,
			createdAt = now,
			updatedAt = now,
		)

		fun createImported(
			id: UUID,
			userId: UUID,
			company: String,
			position: String,
			location: String,
			employmentType: String,
			appliedAt: LocalDate,
			stage: ApplicationStage,
			highestStageReached: ApplicationStage,
			screeningPassed: Boolean,
			result: ApplicationResult,
			needsReview: Boolean,
			source: String,
			sourceType: ApplicationSourceType,
			creationMutationId: UUID,
			now: Instant,
		): JobApplication = JobApplication(
			id = id,
			userId = userId,
			company = company,
			position = position,
			location = location,
			employmentType = employmentType,
			appliedAt = appliedAt,
			stage = stage,
			highestStageReached = highestStageReached,
			screeningPassed = screeningPassed,
			result = result,
			needsReview = needsReview,
			source = source,
			sourceType = sourceType,
			memo = "",
			creationMutationId = creationMutationId,
			lastMutationId = creationMutationId,
			version = 0,
			createdAt = now,
			updatedAt = now,
		)
	}
}
