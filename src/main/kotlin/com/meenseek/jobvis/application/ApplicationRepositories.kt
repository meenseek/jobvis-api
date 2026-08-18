package com.meenseek.jobvis.application

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import java.time.Instant
import java.util.UUID

interface JobApplicationRepository : JpaRepository<JobApplication, UUID> {
	@Query(
		value = """
			SELECT application.*
			FROM applications application
			WHERE application.user_id = :userId
			  AND (
			      :queryText = ''
			      OR lower(application.company) LIKE ('%' || :queryText || '%')
			      OR lower(application.position) LIKE ('%' || :queryText || '%')
			      OR lower(application.source) LIKE ('%' || :queryText || '%')
			  )
			  AND (
			      :status = 'all'
			      OR (:status = 'review' AND application.needs_review)
			      OR (:status = 'offered' AND application.result = 'OFFERED')
			      OR (:status = 'rejected' AND application.result = 'REJECTED')
			      OR (
			          :status IN ('applied', 'screening', 'interview', 'offer')
			          AND application.result = 'ACTIVE'
			          AND lower(application.stage) = :status
			      )
			  )
			ORDER BY application.applied_at DESC, application.created_at DESC, application.id ASC
		""",
		nativeQuery = true,
	)
	fun findListItems(
		@Param("userId") userId: UUID,
		@Param("queryText") queryText: String,
		@Param("status") status: String,
		pageable: Pageable,
	): Slice<JobApplication>

	@Query(
		"SELECT application FROM JobApplication application " +
			"WHERE application.storedId = :id AND application.storedUserId = :userId",
	)
	fun findOwned(@Param("id") id: UUID, @Param("userId") userId: UUID): JobApplication?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(
		"SELECT application FROM JobApplication application " +
			"WHERE application.storedId = :id AND application.storedUserId = :userId",
	)
	fun findOwnedLocked(@Param("id") id: UUID, @Param("userId") userId: UUID): JobApplication?
}

interface ApplicationScheduleRepository : JpaRepository<ApplicationSchedule, UUID> {
	@Query(
		"SELECT schedule FROM ApplicationSchedule schedule " +
			"WHERE schedule.storedUserId = :userId AND schedule.storedApplicationId IN :applicationIds",
	)
	fun findAllForApplications(
		@Param("userId") userId: UUID,
		@Param("applicationIds") applicationIds: Collection<UUID>,
	): List<ApplicationSchedule>

	@Query(
		"SELECT schedule FROM ApplicationSchedule schedule " +
			"WHERE schedule.storedUserId = :userId AND schedule.storedApplicationId = :applicationId",
	)
	fun findForApplication(
		@Param("userId") userId: UUID,
		@Param("applicationId") applicationId: UUID,
	): ApplicationSchedule?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(
		"SELECT schedule FROM ApplicationSchedule schedule " +
			"WHERE schedule.storedUserId = :userId AND schedule.storedApplicationId = :applicationId",
	)
	fun findForApplicationLocked(
		@Param("userId") userId: UUID,
		@Param("applicationId") applicationId: UUID,
	): ApplicationSchedule?

	@Query(
		"SELECT schedule FROM ApplicationSchedule schedule " +
			"WHERE schedule.storedId = :id AND schedule.storedUserId = :userId",
	)
	fun findOwned(@Param("id") id: UUID, @Param("userId") userId: UUID): ApplicationSchedule?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(
		"SELECT schedule FROM ApplicationSchedule schedule " +
			"WHERE schedule.storedId = :id AND schedule.storedUserId = :userId",
	)
	fun findOwnedLocked(@Param("id") id: UUID, @Param("userId") userId: UUID): ApplicationSchedule?
}

interface ApplicationEmailRepository : JpaRepository<ApplicationEmail, UUID> {
	fun findAllByUserIdAndApplicationIdAndRecordedOrderLessThanEqualOrderByRecordedOrderDesc(
		userId: UUID,
		applicationId: UUID,
		recordedOrder: Long,
		pageable: Pageable,
	): List<ApplicationEmail>

	fun findAllByUserIdAndApplicationIdAndRecordedOrderLessThanOrderByRecordedOrderDesc(
		userId: UUID,
		applicationId: UUID,
		recordedOrder: Long,
		pageable: Pageable,
	): List<ApplicationEmail>
}

interface ApplicationActivityRepository : JpaRepository<ApplicationActivity, UUID> {
	fun findAllByUserIdAndApplicationIdAndRecordedOrderLessThanEqualOrderByRecordedOrderDesc(
		userId: UUID,
		applicationId: UUID,
		recordedOrder: Long,
		pageable: Pageable,
	): List<ApplicationActivity>

	fun findAllByUserIdAndApplicationIdAndRecordedOrderLessThanOrderByRecordedOrderDesc(
		userId: UUID,
		applicationId: UUID,
		recordedOrder: Long,
		pageable: Pageable,
	): List<ApplicationActivity>
}

interface ApplicationChangeRepository : JpaRepository<ApplicationChange, UUID> {
	fun findAllByUserIdAndApplicationIdAndRecordedOrderLessThanEqualOrderByRecordedOrderDesc(
		userId: UUID,
		applicationId: UUID,
		recordedOrder: Long,
		pageable: Pageable,
	): List<ApplicationChange>

	fun findAllByUserIdAndApplicationIdAndRecordedOrderLessThanOrderByRecordedOrderDesc(
		userId: UUID,
		applicationId: UUID,
		recordedOrder: Long,
		pageable: Pageable,
	): List<ApplicationChange>
}

interface ApplicationMutationRepository : JpaRepository<ApplicationMutation, UUID> {
	@Query(
		value = """
			SELECT COALESCE(MAX(history.recorded_order), 0)
			FROM (
			    SELECT recorded_order FROM application_emails
			    WHERE user_id = :userId AND application_id = :applicationId
			    UNION ALL
			    SELECT recorded_order FROM application_activities
			    WHERE user_id = :userId AND application_id = :applicationId
			    UNION ALL
			    SELECT recorded_order FROM application_changes
			    WHERE user_id = :userId AND application_id = :applicationId
			) history
		""",
		nativeQuery = true,
	)
	fun historyWatermark(
		@Param("userId") userId: UUID,
		@Param("applicationId") applicationId: UUID,
	): Long

	@Modifying
	@Query(
		value = """
			INSERT INTO application_mutations (
				    id, user_id, mutation_id, application_id, operation,
				    request_fingerprint, resulting_version, history_watermark,
				    result_payload, created_at, completed_at
				) VALUES (
				    :id, :userId, :mutationId, NULL, :operation,
				    :requestFingerprint, NULL, NULL, NULL, :createdAt, NULL
			)
			ON CONFLICT (user_id, mutation_id) DO NOTHING
		""",
		nativeQuery = true,
	)
	fun reserve(
		@Param("id") id: UUID,
		@Param("userId") userId: UUID,
		@Param("mutationId") mutationId: UUID,
		@Param("operation") operation: String,
		@Param("requestFingerprint") requestFingerprint: String,
		@Param("createdAt") createdAt: Instant,
	): Int

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(
		"SELECT mutation FROM ApplicationMutation mutation " +
			"WHERE mutation.storedUserId = :userId AND mutation.storedMutationId = :mutationId",
	)
	fun findLocked(
		@Param("userId") userId: UUID,
		@Param("mutationId") mutationId: UUID,
	): ApplicationMutation?
}
