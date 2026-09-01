DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM mail_finalization_rollout_state
        WHERE singleton = true AND completed_at IS NULL
    )
    AND NOT EXISTS (SELECT 1 FROM import_drafts)
    AND NOT EXISTS (SELECT 1 FROM mail_ingestion_ledger) THEN
        ALTER TABLE mail_ingestion_ledger
            DROP CONSTRAINT ck_mail_ingestion_ledger_state_expand;
        ALTER TABLE mail_ingestion_ledger
            ADD CONSTRAINT ck_mail_ingestion_ledger_state_target CHECK (
                (state = 'FINALIZED' AND application_id IS NOT NULL)
                OR (state = 'IGNORED' AND application_id IS NULL)
            );

        ALTER TABLE import_drafts
            DROP CONSTRAINT ck_import_drafts_status_expand;
        ALTER TABLE import_drafts
            DROP CONSTRAINT ck_import_drafts_decision_expand;
        ALTER TABLE import_drafts
            ADD CONSTRAINT ck_import_drafts_status_target
                CHECK (status IN ('ACCEPTED', 'REJECTED', 'FAILED'));
        ALTER TABLE import_drafts
            ADD CONSTRAINT ck_import_drafts_decision_target CHECK (
                (status = 'REJECTED' AND decided_at IS NOT NULL
                    AND accepted_application_id IS NULL
                    AND decision_mutation_id IS NOT NULL
                    AND decision_fingerprint IS NOT NULL
                    AND error_code IS NULL)
                OR
                (status = 'ACCEPTED' AND decided_at IS NOT NULL
                    AND accepted_application_id IS NOT NULL
                    AND error_code IS NULL)
                OR
                (status = 'FAILED' AND decided_at IS NOT NULL
                    AND accepted_application_id IS NULL
                    AND error_code IS NOT NULL)
            );

        UPDATE mail_finalization_rollout_state
        SET completed_at = clock_timestamp(), updated_at = clock_timestamp(),
            pending_draft_count = 0, drafted_ledger_count = 0
        WHERE singleton = true AND completed_at IS NULL;
    END IF;
END $$;
