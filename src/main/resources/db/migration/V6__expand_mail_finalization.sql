DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM import_drafts WHERE status = 'PENDING') THEN
        RAISE EXCEPTION
            'V6 requires every legacy PENDING import draft to be accepted or rejected before deployment';
    END IF;
END
$$;

ALTER TABLE applications
    ADD COLUMN source_type VARCHAR(20);

UPDATE applications
SET source_type = CASE
    WHEN lower(btrim(source)) = '직접 추가' THEN 'MANUAL'
    WHEN lower(btrim(source)) = 'gmail 메일' THEN 'GMAIL'
    WHEN lower(btrim(source)) = 'naver 메일' THEN 'NAVER'
    WHEN lower(btrim(source)) = 'outlook 메일' THEN 'OUTLOOK'
    ELSE 'OTHER'
END;

ALTER TABLE applications
    ALTER COLUMN source_type SET NOT NULL,
    ADD CONSTRAINT ck_applications_source_type
        CHECK (source_type IN ('MANUAL', 'GMAIL', 'OUTLOOK', 'NAVER', 'OTHER'));

CREATE TABLE application_review_states (
    user_id UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    review_revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_application_review_states_revision CHECK (review_revision >= 0)
);

INSERT INTO application_review_states (user_id, review_revision, updated_at)
SELECT id, 0, clock_timestamp()
FROM users;

CREATE TABLE provider_process_bindings (
    user_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    provider_process_key VARCHAR(128) NOT NULL,
    application_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, connection_id, provider_process_key),
    CONSTRAINT fk_provider_process_bindings_connection
        FOREIGN KEY (user_id, connection_id)
        REFERENCES external_connections (user_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_provider_process_bindings_application
        FOREIGN KEY (user_id, application_id)
        REFERENCES applications (user_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_provider_process_bindings_application
    ON provider_process_bindings (user_id, application_id, provider_process_key);

ALTER TABLE mail_ingestion_ledger
    ADD COLUMN stable_provider_message_key VARCHAR(64),
    ADD COLUMN application_id UUID,
    DROP CONSTRAINT ck_mail_ingestion_ledger_state,
    ADD CONSTRAINT ck_mail_ingestion_ledger_state_expand
        CHECK (state IN ('DRAFTED', 'ACCEPTED', 'REJECTED', 'FINALIZED', 'IGNORED')),
    ADD CONSTRAINT fk_mail_ingestion_ledger_application
        FOREIGN KEY (user_id, application_id)
        REFERENCES applications (user_id, id);

CREATE UNIQUE INDEX uq_mail_ingestion_ledger_stable_message
    ON mail_ingestion_ledger (user_id, connection_id, stable_provider_message_key)
    WHERE stable_provider_message_key IS NOT NULL;

ALTER TABLE import_drafts
    ADD COLUMN error_code VARCHAR(80),
    DROP CONSTRAINT ck_import_drafts_status,
    DROP CONSTRAINT ck_import_drafts_decision,
    ADD CONSTRAINT ck_import_drafts_status_expand
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'FAILED')),
    ADD CONSTRAINT ck_import_drafts_decision_expand CHECK (
        (status = 'PENDING' AND decided_at IS NULL AND accepted_application_id IS NULL
            AND error_code IS NULL)
        OR
        (status = 'REJECTED' AND decided_at IS NOT NULL AND accepted_application_id IS NULL
            AND decision_mutation_id IS NOT NULL AND decision_fingerprint IS NOT NULL
            AND error_code IS NULL)
        OR
        (status = 'ACCEPTED' AND decided_at IS NOT NULL AND accepted_application_id IS NOT NULL
            AND error_code IS NULL)
        OR
        (status = 'FAILED' AND decided_at IS NOT NULL AND accepted_application_id IS NULL
            AND error_code IS NOT NULL)
    );

UPDATE external_connections connection
SET status = CASE WHEN connection.status = 'REVOKED' THEN 'REVOKED' ELSE 'ERROR' END,
    ongoing_sync_consent = false,
    next_sync_after = NULL,
    last_error_code = 'NAVER_LEDGER_MIGRATION_REQUIRED',
    updated_at = clock_timestamp(),
    version = version + 1
WHERE connection.provider = 'NAVER'
  AND EXISTS (
      SELECT 1
      FROM mail_ingestion_ledger ledger
      WHERE ledger.user_id = connection.user_id
        AND ledger.connection_id = connection.id
        AND ledger.stable_provider_message_key IS NULL
  );
