-- Stock reservations of unpaid orders now expire (A02).
ALTER TABLE orders
    ADD COLUMN reservation_expires_at TIMESTAMPTZ,
    ADD COLUMN reservation_review_at TIMESTAMPTZ;
-- Orders already waiting for payment get a full window from the moment this migration runs.
UPDATE orders SET reservation_expires_at = now() + interval '24 hours' WHERE status = 'PENDING_PAYMENT';
CREATE INDEX ix_orders_reservation ON orders (reservation_expires_at) WHERE status = 'PENDING_PAYMENT';

-- Recoverable email outbox (A04). The *_email_sent_at columns on orders remain as "accepted by SMTP" markers.
CREATE TABLE order_emails (
    id              UUID PRIMARY KEY,
    order_id        UUID        NOT NULL REFERENCES orders (id),
    kind            VARCHAR(20) NOT NULL CHECK (kind IN ('CONFIRMATION', 'SHIPMENT')),
    status          VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_until   TIMESTAMPTZ,
    last_error      VARCHAR(120),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    UNIQUE (order_id, kind)
);
CREATE INDEX ix_order_emails_due ON order_emails (next_attempt_at) WHERE status IN ('PENDING', 'PROCESSING');

-- Password recovery and email verification (A08). Only the HMAC of each token is stored.
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;
CREATE TABLE account_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    VARCHAR(24) NOT NULL CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION')),
    token_hash VARCHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_account_tokens_user ON account_tokens (user_id, purpose);

-- Hosted databases use a separate unprivileged runtime role (deploy/neon). Default privileges only give it
-- SELECT/INSERT on new tables, so the outbox and token tables need their write grants here.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'olvenio_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE order_emails, account_tokens TO olvenio_runtime;
    END IF;
END $$;
