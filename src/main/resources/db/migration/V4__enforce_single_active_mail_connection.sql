DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM external_connections
        WHERE provider IN ('GMAIL', 'OUTLOOK', 'NAVER')
          AND status <> 'REVOKED'
        GROUP BY user_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'V4 requires explicit reconciliation of users with multiple active MAIL connections';
    END IF;
END
$$;

CREATE UNIQUE INDEX uq_external_connections_user_active_mail
    ON external_connections (user_id)
    WHERE provider IN ('GMAIL', 'OUTLOOK', 'NAVER')
      AND status <> 'REVOKED';
