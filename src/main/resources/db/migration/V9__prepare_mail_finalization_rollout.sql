CREATE TABLE mail_finalization_rollout_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    completed_at TIMESTAMPTZ,
    pending_draft_count BIGINT NOT NULL,
    drafted_ledger_count BIGINT NOT NULL,
    orphan_drafted_deleted_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL
);

INSERT INTO mail_finalization_rollout_state (
    singleton, completed_at, pending_draft_count, drafted_ledger_count,
    orphan_drafted_deleted_count, updated_at
)
SELECT true, NULL, draft_count, ledger_count, 0, clock_timestamp()
FROM (SELECT count(*) AS draft_count FROM import_drafts WHERE status = 'PENDING') draft,
     (SELECT count(*) AS ledger_count FROM mail_ingestion_ledger WHERE state = 'DRAFTED') ledger;

ALTER TABLE mail_ingestion_ledger
    ADD CONSTRAINT uq_mail_ingestion_ledger_owner_id UNIQUE (user_id, connection_id, id);

CREATE TABLE naver_ledger_reconciliation_runs (
    operation_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    ledger_count BIGINT,
    stable_key_count BIGINT,
    verified_uid_only_count BIGINT,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_naver_ledger_reconciliation_run_connection
        FOREIGN KEY (user_id, connection_id)
        REFERENCES external_connections (user_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_naver_ledger_reconciliation_run_completion CHECK (
        (completed_at IS NULL AND ledger_count IS NULL AND stable_key_count IS NULL
            AND verified_uid_only_count IS NULL)
        OR
        (completed_at IS NOT NULL AND ledger_count IS NOT NULL AND stable_key_count IS NOT NULL
            AND verified_uid_only_count IS NOT NULL)
    )
);

CREATE TABLE naver_ledger_reconciliation_audits (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    ledger_id UUID NOT NULL UNIQUE,
    disposition VARCHAR(30) NOT NULL,
    stable_provider_message_key VARCHAR(64),
    evidence_type VARCHAR(30) NOT NULL,
    evidence_reference VARCHAR(500) NOT NULL,
    reconciled_by VARCHAR(160) NOT NULL,
    reconciled_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_naver_ledger_reconciliation_connection
        FOREIGN KEY (user_id, connection_id)
        REFERENCES external_connections (user_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_naver_ledger_reconciliation_run
        FOREIGN KEY (operation_id)
        REFERENCES naver_ledger_reconciliation_runs (operation_id) ON DELETE CASCADE,
    CONSTRAINT fk_naver_ledger_reconciliation_ledger
        FOREIGN KEY (user_id, connection_id, ledger_id)
        REFERENCES mail_ingestion_ledger (user_id, connection_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_naver_ledger_reconciliation_disposition CHECK (
        (disposition = 'STABLE_KEY' AND stable_provider_message_key IS NOT NULL)
        OR (disposition = 'VERIFIED_UID_ONLY' AND stable_provider_message_key IS NULL)
    ),
    CONSTRAINT ck_naver_ledger_reconciliation_evidence
        CHECK (evidence_type IN ('PROVIDER_REFETCH', 'PROVIDER_EXPORT', 'USER_CONFIRMED'))
);

CREATE INDEX ix_naver_ledger_reconciliation_connection
    ON naver_ledger_reconciliation_audits (user_id, connection_id, ledger_id);
