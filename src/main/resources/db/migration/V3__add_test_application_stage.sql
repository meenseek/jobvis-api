DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM import_drafts WHERE status = 'PENDING') THEN
        RAISE EXCEPTION
            'V3 preflight requires every legacy PENDING import draft to be accepted or rejected';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM external_connections
        WHERE provider IN ('GMAIL', 'OUTLOOK', 'NAVER')
          AND status <> 'REVOKED'
        GROUP BY user_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V3 preflight requires explicit reconciliation of users with multiple active MAIL connections';
    END IF;

    IF EXISTS (SELECT 1 FROM application_schedules WHERE scheduled_at IS NULL) THEN
        RAISE EXCEPTION
            'V3 preflight requires every legacy application schedule to have scheduled_at';
    END IF;
END
$$;

ALTER TABLE applications
    DROP CONSTRAINT ck_applications_stage,
    DROP CONSTRAINT ck_applications_highest_stage,
    DROP CONSTRAINT ck_applications_stage_not_above_highest,
    DROP CONSTRAINT ck_applications_advanced_stage_passed,
    DROP CONSTRAINT ck_applications_offered_state;

UPDATE applications AS application
SET stage = 'TEST',
    highest_stage_reached = CASE
        WHEN application.highest_stage_reached IN ('APPLIED', 'SCREENING') THEN 'TEST'
        ELSE application.highest_stage_reached
    END
FROM application_schedules AS schedule
WHERE schedule.user_id = application.user_id
  AND schedule.application_id = application.id
  AND schedule.schedule_type = 'TEST'
  AND application.result = 'ACTIVE'
  AND application.stage IN ('APPLIED', 'SCREENING');

ALTER TABLE applications
    ADD CONSTRAINT ck_applications_stage
        CHECK (stage IN ('APPLIED', 'SCREENING', 'TEST', 'INTERVIEW', 'OFFER')),
    ADD CONSTRAINT ck_applications_highest_stage
        CHECK (highest_stage_reached IN ('APPLIED', 'SCREENING', 'TEST', 'INTERVIEW', 'OFFER')),
    ADD CONSTRAINT ck_applications_stage_not_above_highest CHECK (
        CASE stage
            WHEN 'APPLIED' THEN 0
            WHEN 'SCREENING' THEN 1
            WHEN 'TEST' THEN 2
            WHEN 'INTERVIEW' THEN 3
            WHEN 'OFFER' THEN 4
        END
        <=
        CASE highest_stage_reached
            WHEN 'APPLIED' THEN 0
            WHEN 'SCREENING' THEN 1
            WHEN 'TEST' THEN 2
            WHEN 'INTERVIEW' THEN 3
            WHEN 'OFFER' THEN 4
        END
    ),
    ADD CONSTRAINT ck_applications_advanced_stage_passed CHECK (
        highest_stage_reached NOT IN ('INTERVIEW', 'OFFER') OR screening_passed
    ),
    ADD CONSTRAINT ck_applications_offered_state CHECK (
        result <> 'OFFERED'
        OR (
            stage = 'OFFER'
            AND highest_stage_reached = 'OFFER'
            AND screening_passed
        )
    );

ALTER TABLE import_drafts
    DROP CONSTRAINT ck_import_drafts_stage,
    DROP CONSTRAINT ck_import_drafts_highest_stage,
    DROP CONSTRAINT ck_import_drafts_progress;

ALTER TABLE import_drafts
    ADD CONSTRAINT ck_import_drafts_stage
        CHECK (stage IN ('APPLIED', 'SCREENING', 'TEST', 'INTERVIEW', 'OFFER')),
    ADD CONSTRAINT ck_import_drafts_highest_stage
        CHECK (highest_stage_reached IN ('APPLIED', 'SCREENING', 'TEST', 'INTERVIEW', 'OFFER')),
    ADD CONSTRAINT ck_import_drafts_progress CHECK (
        CASE stage
            WHEN 'APPLIED' THEN 0
            WHEN 'SCREENING' THEN 1
            WHEN 'TEST' THEN 2
            WHEN 'INTERVIEW' THEN 3
            WHEN 'OFFER' THEN 4
        END
        <=
        CASE highest_stage_reached
            WHEN 'APPLIED' THEN 0
            WHEN 'SCREENING' THEN 1
            WHEN 'TEST' THEN 2
            WHEN 'INTERVIEW' THEN 3
            WHEN 'OFFER' THEN 4
        END
        AND (highest_stage_reached NOT IN ('INTERVIEW', 'OFFER') OR screening_passed)
        AND (result <> 'OFFERED' OR (
            stage = 'OFFER' AND highest_stage_reached = 'OFFER' AND screening_passed
        ))
    );
