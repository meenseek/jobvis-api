CREATE TABLE users (
    id UUID PRIMARY KEY,
    display_name VARCHAR(120),
    primary_email VARCHAR(320),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE auth_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    email VARCHAR(320),
    email_verified BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_auth_identities_provider_subject UNIQUE (provider, subject),
    CONSTRAINT uq_auth_identities_user_provider UNIQUE (user_id, provider),
    CONSTRAINT ck_auth_identities_provider CHECK (provider IN ('GOOGLE', 'KAKAO'))
);

CREATE INDEX ix_auth_identities_user
    ON auth_identities (user_id, provider);

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_auth_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_auth_sessions_revoked CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX ix_auth_sessions_user_active
    ON auth_sessions (user_id, expires_at DESC)
    WHERE revoked_at IS NULL;

CREATE TABLE login_challenges (
    id UUID PRIMARY KEY,
    provider VARCHAR(20) NOT NULL,
    challenge_hash VARCHAR(64) NOT NULL,
    nonce_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_login_challenges_hash UNIQUE (challenge_hash),
    CONSTRAINT ck_login_challenges_provider CHECK (provider IN ('GOOGLE', 'KAKAO')),
    CONSTRAINT ck_login_challenges_consumed CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

CREATE INDEX ix_login_challenges_expiry
    ON login_challenges (expires_at, id);

CREATE TABLE oauth_challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    flow_type VARCHAR(30) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    state_hash VARCHAR(64) NOT NULL,
    encrypted_pkce_verifier TEXT NOT NULL,
    redirect_uri VARCHAR(1000) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    exchange_claim_token UUID,
    exchange_claim_expires_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_oauth_challenges_state_hash UNIQUE (state_hash),
    CONSTRAINT ck_oauth_challenges_flow CHECK (
        flow_type IN ('MAIL_CONNECTION', 'CALENDAR_CONNECTION')
    ),
    CONSTRAINT ck_oauth_challenges_provider CHECK (
        provider IN ('GOOGLE', 'MICROSOFT')
    ),
    CONSTRAINT ck_oauth_challenges_consumed CHECK (consumed_at IS NULL OR consumed_at >= created_at),
    CONSTRAINT ck_oauth_challenges_exchange_claim CHECK (
        (exchange_claim_token IS NULL AND exchange_claim_expires_at IS NULL)
        OR
        (exchange_claim_token IS NOT NULL AND exchange_claim_expires_at IS NOT NULL AND consumed_at IS NULL)
    )
);

CREATE INDEX ix_oauth_challenges_expires_at
    ON oauth_challenges (expires_at)
    WHERE consumed_at IS NULL;
CREATE INDEX ix_oauth_challenges_user_outstanding
    ON oauth_challenges (user_id, expires_at, id)
    WHERE consumed_at IS NULL;
CREATE INDEX ix_oauth_challenges_user_created_at
    ON oauth_challenges (user_id, created_at DESC, id);

CREATE TABLE applications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    company VARCHAR(160) NOT NULL,
    position VARCHAR(160) NOT NULL,
    location VARCHAR(160) NOT NULL,
    employment_type VARCHAR(80) NOT NULL,
    applied_at DATE NOT NULL,
    stage VARCHAR(20) NOT NULL,
    highest_stage_reached VARCHAR(20) NOT NULL,
    screening_passed BOOLEAN NOT NULL,
    result VARCHAR(20) NOT NULL,
    needs_review BOOLEAN NOT NULL,
    source VARCHAR(80) NOT NULL,
    memo TEXT NOT NULL,
    creation_mutation_id UUID NOT NULL,
    last_mutation_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_applications_user_id_id UNIQUE (user_id, id),
    CONSTRAINT uq_applications_creation_mutation UNIQUE (user_id, creation_mutation_id),
    CONSTRAINT ck_applications_stage CHECK (stage IN ('APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER')),
    CONSTRAINT ck_applications_highest_stage CHECK (
        highest_stage_reached IN ('APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER')
    ),
    CONSTRAINT ck_applications_result CHECK (result IN ('ACTIVE', 'OFFERED', 'REJECTED')),
    CONSTRAINT ck_applications_stage_not_above_highest CHECK (
        CASE stage
            WHEN 'APPLIED' THEN 0
            WHEN 'SCREENING' THEN 1
            WHEN 'INTERVIEW' THEN 2
            WHEN 'OFFER' THEN 3
        END
        <=
        CASE highest_stage_reached
            WHEN 'APPLIED' THEN 0
            WHEN 'SCREENING' THEN 1
            WHEN 'INTERVIEW' THEN 2
            WHEN 'OFFER' THEN 3
        END
    ),
    CONSTRAINT ck_applications_advanced_stage_passed CHECK (
        highest_stage_reached NOT IN ('INTERVIEW', 'OFFER') OR screening_passed
    ),
    CONSTRAINT ck_applications_offered_state CHECK (
        result <> 'OFFERED'
        OR (
            stage = 'OFFER'
            AND highest_stage_reached = 'OFFER'
            AND screening_passed
        )
    )
);

CREATE INDEX ix_applications_user_applied_at
    ON applications (user_id, applied_at DESC, created_at DESC, id);
CREATE INDEX ix_applications_user_status
    ON applications (user_id, result, stage, needs_review);

CREATE TABLE external_connections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider VARCHAR(30) NOT NULL,
    account_email VARCHAR(320) NOT NULL,
    credential_kind VARCHAR(20) NOT NULL,
    encrypted_access_token TEXT,
    encrypted_refresh_token TEXT,
    encrypted_app_password TEXT,
    token_expires_at TIMESTAMPTZ,
    granted_scopes TEXT NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL,
    ongoing_sync_consent BOOLEAN NOT NULL DEFAULT false,
    consented_at TIMESTAMPTZ NOT NULL,
    last_synced_at TIMESTAMPTZ,
    next_sync_after TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_external_connections_user_id UNIQUE (user_id, id),
    CONSTRAINT uq_external_connections_account UNIQUE (user_id, provider, account_email),
    CONSTRAINT ck_external_connections_provider CHECK (
        provider IN ('GMAIL', 'OUTLOOK', 'NAVER', 'GOOGLE_CALENDAR')
    ),
    CONSTRAINT ck_external_connections_credential_kind CHECK (
        credential_kind IN ('OAUTH2', 'APP_PASSWORD')
    ),
    CONSTRAINT ck_external_connections_status CHECK (
        status IN ('CONNECTED', 'REAUTHORIZATION_REQUIRED', 'ERROR', 'REVOKED')
    ),
    CONSTRAINT ck_external_connections_credentials CHECK (
        (
            credential_kind = 'OAUTH2'
            AND encrypted_app_password IS NULL
            AND (status = 'REVOKED' OR encrypted_access_token IS NOT NULL OR encrypted_refresh_token IS NOT NULL)
        )
        OR
        (
            credential_kind = 'APP_PASSWORD'
            AND encrypted_access_token IS NULL
            AND encrypted_refresh_token IS NULL
            AND (status = 'REVOKED' OR encrypted_app_password IS NOT NULL)
        )
    ),
    CONSTRAINT ck_external_connections_revoked CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL
            AND encrypted_access_token IS NULL
            AND encrypted_refresh_token IS NULL
            AND encrypted_app_password IS NULL
            AND ongoing_sync_consent = false)
        OR
        (status <> 'REVOKED' AND revoked_at IS NULL)
    )
);

CREATE INDEX ix_external_connections_user_status
    ON external_connections (user_id, status, provider);
CREATE INDEX ix_external_connections_sync_due
    ON external_connections (next_sync_after, id)
    WHERE status = 'CONNECTED' AND ongoing_sync_consent = true;

CREATE TABLE connection_refresh_claims (
    connection_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    connection_version BIGINT NOT NULL,
    claim_token UUID NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_connection_refresh_claims_connection
        FOREIGN KEY (user_id, connection_id)
        REFERENCES external_connections (user_id, id) ON DELETE CASCADE
);

CREATE TABLE naver_validation_attempts (
    attempt_key VARCHAR(400) PRIMARY KEY,
    attempt_count INTEGER NOT NULL,
    window_expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_naver_validation_attempts_count CHECK (attempt_count > 0)
);

CREATE INDEX ix_naver_validation_attempts_expiry
    ON naver_validation_attempts (window_expires_at, attempt_key);

CREATE TABLE application_schedules (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    application_id UUID NOT NULL,
    schedule_type VARCHAR(20) NOT NULL,
    action VARCHAR(200) NOT NULL,
    scheduled_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Seoul',
    location VARCHAR(300) NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    last_import_received_at TIMESTAMPTZ,
    manually_edited BOOLEAN NOT NULL DEFAULT false,
    completed BOOLEAN NOT NULL,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_application_schedules_application
        FOREIGN KEY (user_id, application_id) REFERENCES applications (user_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_application_schedules_one_per_application UNIQUE (user_id, application_id),
    CONSTRAINT uq_application_schedules_user_id UNIQUE (user_id, id),
    CONSTRAINT ck_application_schedules_type
        CHECK (schedule_type IN ('APPLICATION', 'TEST', 'INTERVIEW', 'FOLLOWUP', 'OTHER')),
    CONSTRAINT ck_application_schedules_completion
        CHECK ((completed AND completed_at IS NOT NULL) OR (NOT completed AND completed_at IS NULL)),
    CONSTRAINT ck_application_schedules_range CHECK (
        ends_at IS NULL OR scheduled_at IS NULL OR ends_at >= scheduled_at
    )
);

CREATE INDEX ix_application_schedules_user_scheduled_at
    ON application_schedules (user_id, completed, scheduled_at, application_id, id);

CREATE SEQUENCE application_history_order_seq;

CREATE TABLE application_emails (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    application_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_message_id VARCHAR(255) NOT NULL,
    subject VARCHAR(500) NOT NULL,
    sender VARCHAR(320) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    summary TEXT NOT NULL,
    recorded_order BIGINT NOT NULL DEFAULT nextval('application_history_order_seq'),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_application_emails_application
        FOREIGN KEY (user_id, application_id) REFERENCES applications (user_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_application_emails_connection
        FOREIGN KEY (user_id, connection_id) REFERENCES external_connections (user_id, id),
    CONSTRAINT uq_application_emails_connection_message
        UNIQUE (user_id, connection_id, provider_message_id)
);

CREATE INDEX ix_application_emails_application_received_at
    ON application_emails (user_id, application_id, received_at DESC, id);
CREATE INDEX ix_application_emails_application_recorded_order
    ON application_emails (user_id, application_id, recorded_order DESC);

CREATE TABLE application_activities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    application_id UUID NOT NULL,
    activity_type VARCHAR(20) NOT NULL,
    title VARCHAR(120) NOT NULL,
    description TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_order BIGINT NOT NULL DEFAULT nextval('application_history_order_seq'),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_application_activities_application
        FOREIGN KEY (user_id, application_id) REFERENCES applications (user_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_application_activities_type CHECK (activity_type IN ('EMAIL', 'NOTE', 'STATUS', 'TASK'))
);

CREATE INDEX ix_application_activities_application_occurred_at
    ON application_activities (user_id, application_id, occurred_at DESC, id);
CREATE INDEX ix_application_activities_application_recorded_order
    ON application_activities (user_id, application_id, recorded_order DESC);

CREATE TABLE application_changes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    application_id UUID NOT NULL,
    mutation_id UUID NOT NULL,
    field_key VARCHAR(40) NOT NULL,
    title VARCHAR(120) NOT NULL,
    before_value TEXT NOT NULL,
    after_value TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_order BIGINT NOT NULL DEFAULT nextval('application_history_order_seq'),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_application_changes_application
        FOREIGN KEY (user_id, application_id) REFERENCES applications (user_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_application_changes_mutation_field UNIQUE (user_id, application_id, mutation_id, field_key)
);

CREATE INDEX ix_application_changes_application_occurred_at
    ON application_changes (user_id, application_id, occurred_at DESC, id);
CREATE INDEX ix_application_changes_application_recorded_order
    ON application_changes (user_id, application_id, recorded_order DESC);

CREATE TABLE application_mutations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    mutation_id UUID NOT NULL,
    application_id UUID,
    operation VARCHAR(40) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    resulting_version BIGINT,
    history_watermark BIGINT,
    result_payload TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_application_mutations_application
        FOREIGN KEY (user_id, application_id) REFERENCES applications (user_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_application_mutations_user_mutation UNIQUE (user_id, mutation_id),
    CONSTRAINT ck_application_mutations_completion CHECK (
        (application_id IS NULL AND resulting_version IS NULL AND history_watermark IS NULL
            AND completed_at IS NULL AND result_payload IS NULL)
        OR
        (application_id IS NOT NULL AND resulting_version IS NOT NULL AND history_watermark IS NOT NULL
            AND completed_at IS NOT NULL
            AND result_payload IS NOT NULL)
    )
);

CREATE INDEX ix_application_mutations_application_created_at
    ON application_mutations (user_id, application_id, created_at DESC);

CREATE TABLE import_runs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    connection_version BIGINT NOT NULL,
    provider VARCHAR(30) NOT NULL,
    requested_by VARCHAR(20) NOT NULL,
    date_from DATE NOT NULL,
    date_to DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    provider_cursor TEXT,
    scanned_count INTEGER NOT NULL DEFAULT 0,
    draft_count INTEGER NOT NULL DEFAULT 0,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(80),
    lease_owner UUID,
    lease_expires_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    purge_after TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_import_runs_user_id UNIQUE (user_id, id),
    CONSTRAINT fk_import_runs_connection
        FOREIGN KEY (user_id, connection_id)
        REFERENCES external_connections (user_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_import_runs_provider CHECK (provider IN ('GMAIL', 'OUTLOOK', 'NAVER')),
    CONSTRAINT ck_import_runs_requested_by CHECK (requested_by IN ('USER', 'MONITOR')),
    CONSTRAINT ck_import_runs_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_import_runs_range CHECK (date_from <= date_to),
    CONSTRAINT ck_import_runs_counts CHECK (
        scanned_count >= 0 AND draft_count >= 0 AND duplicate_count >= 0
    ),
    CONSTRAINT ck_import_runs_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_import_runs_lifecycle CHECK (
        (status = 'QUEUED' AND started_at IS NULL AND completed_at IS NULL
            AND lease_owner IS NULL AND lease_expires_at IS NULL AND heartbeat_at IS NULL)
        OR
        (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL
            AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL AND heartbeat_at IS NOT NULL)
        OR
        (status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND completed_at IS NOT NULL
            AND lease_owner IS NULL AND lease_expires_at IS NULL AND heartbeat_at IS NULL)
    )
);

CREATE INDEX ix_import_runs_user_created_at
    ON import_runs (user_id, created_at DESC, id);
CREATE INDEX ix_import_runs_queue
    ON import_runs (created_at, id)
    WHERE status = 'QUEUED';
CREATE INDEX ix_import_runs_expired_lease
    ON import_runs (lease_expires_at, id)
    WHERE status = 'RUNNING';
CREATE UNIQUE INDEX uq_import_runs_connection_active
    ON import_runs (user_id, connection_id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE import_drafts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    run_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    provider VARCHAR(30) NOT NULL,
    provider_message_id VARCHAR(255) NOT NULL,
    subject VARCHAR(500) NOT NULL,
    sender VARCHAR(320) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    source_summary VARCHAR(1000) NOT NULL,
    company VARCHAR(160) NOT NULL,
    position VARCHAR(160) NOT NULL,
    location VARCHAR(160) NOT NULL,
    employment_type VARCHAR(80) NOT NULL,
    applied_at DATE NOT NULL,
    stage VARCHAR(20) NOT NULL,
    highest_stage_reached VARCHAR(20) NOT NULL,
    screening_passed BOOLEAN NOT NULL,
    result VARCHAR(20) NOT NULL,
    schedule_type VARCHAR(20),
    schedule_action VARCHAR(200),
    scheduled_at TIMESTAMPTZ,
    schedule_ends_at TIMESTAMPTZ,
    confidence NUMERIC(4, 3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    accepted_application_id UUID,
    decision_mutation_id UUID,
    decision_fingerprint VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    decided_at TIMESTAMPTZ,
    purge_after TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_import_drafts_user_id UNIQUE (user_id, id),
    CONSTRAINT uq_import_drafts_provider_message UNIQUE (user_id, connection_id, provider_message_id),
    CONSTRAINT uq_import_drafts_decision_mutation UNIQUE (user_id, decision_mutation_id),
    CONSTRAINT fk_import_drafts_run
        FOREIGN KEY (user_id, run_id) REFERENCES import_runs (user_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_import_drafts_connection
        FOREIGN KEY (user_id, connection_id)
        REFERENCES external_connections (user_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_import_drafts_application
        FOREIGN KEY (user_id, accepted_application_id)
        REFERENCES applications (user_id, id),
    CONSTRAINT ck_import_drafts_provider CHECK (provider IN ('GMAIL', 'OUTLOOK', 'NAVER')),
    CONSTRAINT ck_import_drafts_stage CHECK (stage IN ('APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER')),
    CONSTRAINT ck_import_drafts_highest_stage CHECK (
        highest_stage_reached IN ('APPLIED', 'SCREENING', 'INTERVIEW', 'OFFER')
    ),
    CONSTRAINT ck_import_drafts_result CHECK (result IN ('ACTIVE', 'OFFERED', 'REJECTED')),
    CONSTRAINT ck_import_drafts_progress CHECK (
        CASE stage
            WHEN 'APPLIED' THEN 0
            WHEN 'SCREENING' THEN 1
            WHEN 'INTERVIEW' THEN 2
            WHEN 'OFFER' THEN 3
        END
        <=
        CASE highest_stage_reached
            WHEN 'APPLIED' THEN 0
            WHEN 'SCREENING' THEN 1
            WHEN 'INTERVIEW' THEN 2
            WHEN 'OFFER' THEN 3
        END
        AND (highest_stage_reached NOT IN ('INTERVIEW', 'OFFER') OR screening_passed)
        AND (result <> 'OFFERED' OR (
            stage = 'OFFER' AND highest_stage_reached = 'OFFER' AND screening_passed
        ))
    ),
    CONSTRAINT ck_import_drafts_schedule_type CHECK (
        schedule_type IS NULL OR schedule_type IN ('APPLICATION', 'TEST', 'INTERVIEW', 'FOLLOWUP', 'OTHER')
    ),
    CONSTRAINT ck_import_drafts_schedule_fields CHECK (
        (schedule_type IS NULL AND schedule_action IS NULL AND scheduled_at IS NULL AND schedule_ends_at IS NULL)
        OR
        (schedule_type IS NOT NULL AND schedule_action IS NOT NULL AND scheduled_at IS NOT NULL
            AND (schedule_ends_at IS NULL OR schedule_ends_at >= scheduled_at))
    ),
    CONSTRAINT ck_import_drafts_confidence CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_import_drafts_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_import_drafts_decision CHECK (
        (status = 'PENDING' AND decided_at IS NULL AND accepted_application_id IS NULL
            AND decision_mutation_id IS NULL AND decision_fingerprint IS NULL)
        OR
        (status = 'REJECTED' AND decided_at IS NOT NULL AND accepted_application_id IS NULL
            AND decision_mutation_id IS NOT NULL AND decision_fingerprint IS NOT NULL)
        OR
        (status = 'ACCEPTED' AND decided_at IS NOT NULL AND accepted_application_id IS NOT NULL
            AND decision_mutation_id IS NOT NULL AND decision_fingerprint IS NOT NULL)
    )
);

CREATE INDEX ix_import_drafts_user_status
    ON import_drafts (user_id, status, received_at DESC, id);
CREATE INDEX ix_import_drafts_purge_after
    ON import_drafts (purge_after);

CREATE TABLE mail_ingestion_ledger (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    provider_message_id VARCHAR(255) NOT NULL,
    state VARCHAR(20) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_mail_ingestion_ledger_message
        UNIQUE (user_id, connection_id, provider_message_id),
    CONSTRAINT fk_mail_ingestion_ledger_connection
        FOREIGN KEY (user_id, connection_id)
        REFERENCES external_connections (user_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_mail_ingestion_ledger_state
        CHECK (state IN ('DRAFTED', 'ACCEPTED', 'REJECTED'))
);

CREATE INDEX ix_mail_ingestion_ledger_connection_seen
    ON mail_ingestion_ledger (user_id, connection_id, first_seen_at DESC, id);

CREATE TABLE calendar_exports (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    schedule_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    schedule_version BIGINT NOT NULL,
    preview_hash VARCHAR(64) NOT NULL,
    idempotency_key UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    timezone VARCHAR(80) NOT NULL,
    location VARCHAR(300) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    provider_event_id VARCHAR(255),
    last_error_code VARCHAR(80),
    claim_token UUID,
    claim_expires_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_calendar_exports_user_id UNIQUE (user_id, id),
    CONSTRAINT uq_calendar_exports_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_calendar_exports_schedule
        FOREIGN KEY (user_id, schedule_id)
        REFERENCES application_schedules (user_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_calendar_exports_connection
        FOREIGN KEY (user_id, connection_id)
        REFERENCES external_connections (user_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_calendar_exports_range CHECK (ends_at >= starts_at),
    CONSTRAINT ck_calendar_exports_status CHECK (
        status IN ('PREVIEWED', 'CONFIRMING', 'CONFIRMED', 'FAILED')
    ),
    CONSTRAINT ck_calendar_exports_confirmation CHECK (
        (status = 'CONFIRMING' AND confirmed_at IS NULL AND provider_event_id IS NULL
            AND claim_token IS NOT NULL AND claim_expires_at IS NOT NULL)
        OR
        (status = 'CONFIRMED' AND confirmed_at IS NOT NULL AND provider_event_id IS NOT NULL
            AND claim_token IS NULL AND claim_expires_at IS NULL)
        OR
        (status IN ('PREVIEWED', 'FAILED') AND confirmed_at IS NULL AND provider_event_id IS NULL
            AND claim_token IS NULL AND claim_expires_at IS NULL)
    )
);

CREATE INDEX ix_calendar_exports_schedule_created_at
    ON calendar_exports (user_id, schedule_id, created_at DESC, id);
CREATE INDEX ix_calendar_exports_preview
    ON calendar_exports (user_id, schedule_id, schedule_version, preview_hash);
CREATE INDEX ix_calendar_exports_expired_claim
    ON calendar_exports (claim_expires_at, id)
    WHERE status = 'CONFIRMING';
