ALTER TABLE import_runs
    ADD COLUMN mutation_id UUID,
    ADD COLUMN request_fingerprint VARCHAR(64);

UPDATE import_runs
SET mutation_id = id,
    request_fingerprint = 'legacy:' || id::text
WHERE requested_by = 'USER';

ALTER TABLE import_runs
    ADD CONSTRAINT ck_import_runs_user_mutation CHECK (
        (requested_by = 'USER' AND mutation_id IS NOT NULL AND request_fingerprint IS NOT NULL)
        OR
        (requested_by = 'MONITOR' AND mutation_id IS NULL AND request_fingerprint IS NULL)
    );

CREATE UNIQUE INDEX uq_import_runs_user_mutation
    ON import_runs (user_id, mutation_id)
    WHERE mutation_id IS NOT NULL;
