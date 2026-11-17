-- Closing a checkout is durable and excludes preference creation/recovery under the order lock.
ALTER TABLE payments ADD COLUMN checkout_closed_at TIMESTAMPTZ;

-- An old SMTP worker must not acknowledge a newer tracking correction or another worker's lease.
ALTER TABLE order_emails
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN claim_id UUID;

-- Durable requests contain no bearer token. The application re-derives the token from this random id
-- and its server-only HMAC key; account_tokens still stores only the token's HMAC.
CREATE TABLE account_emails (
    id              UUID PRIMARY KEY,
    email           VARCHAR(320) NOT NULL,
    purpose         VARCHAR(24) NOT NULL CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION')),
    status          VARCHAR(12) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'SKIPPED')),
    attempts        INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_until   TIMESTAMPTZ,
    claim_id        UUID,
    expires_at      TIMESTAMPTZ NOT NULL,
    last_error      VARCHAR(120),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);
CREATE INDEX ix_account_emails_due ON account_emails (next_attempt_at)
    WHERE status IN ('PENDING', 'PROCESSING');

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'olvenio_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE account_emails TO olvenio_runtime;
    END IF;
END $$;
