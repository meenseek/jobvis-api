CREATE TABLE application_bulk_review_mutations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    mutation_id UUID NOT NULL,
    expected_review_revision BIGINT NOT NULL,
    completed_count INTEGER NOT NULL,
    needs_review_count BIGINT NOT NULL,
    resulting_review_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_application_bulk_review_mutation UNIQUE (user_id, mutation_id),
    CONSTRAINT ck_application_bulk_review_counts
        CHECK (completed_count >= 0 AND needs_review_count >= 0),
    CONSTRAINT ck_application_bulk_review_revisions
        CHECK (expected_review_revision >= 0 AND resulting_review_revision >= expected_review_revision)
);
