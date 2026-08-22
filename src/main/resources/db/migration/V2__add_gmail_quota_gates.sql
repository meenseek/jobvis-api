CREATE TABLE gmail_quota_gates (
    account_key CHAR(64) PRIMARY KEY,
    next_permit_at TIMESTAMPTZ NOT NULL,
    blocked_until TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_gmail_quota_gates_account_key
        CHECK (account_key ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_gmail_quota_gates_updated_at
    ON gmail_quota_gates (updated_at);
