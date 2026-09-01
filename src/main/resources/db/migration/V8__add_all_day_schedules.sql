DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM application_schedules WHERE scheduled_at IS NULL) THEN
        RAISE EXCEPTION 'application_schedules contains rows without scheduled_at; reconcile before V8';
    END IF;
END $$;

ALTER TABLE application_schedules
    ADD COLUMN all_day BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN scheduled_date DATE,
    ALTER COLUMN timezone DROP NOT NULL,
    ADD CONSTRAINT ck_application_schedules_temporal_shape CHECK (
        (all_day AND scheduled_date IS NOT NULL AND scheduled_at IS NULL
            AND ends_at IS NULL AND timezone IS NULL)
        OR
        (NOT all_day AND scheduled_date IS NULL AND scheduled_at IS NOT NULL
            AND timezone IS NOT NULL)
    );

CREATE INDEX ix_application_schedules_user_date
    ON application_schedules (user_id, completed, scheduled_date, application_id, id)
    WHERE all_day;
