CREATE TABLE marketing_consents (
    id UUID PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    ttclid VARCHAR(512),
    ttp VARCHAR(512)
);
CREATE INDEX ix_marketing_consents_expiry ON marketing_consents (expires_at);

CREATE TABLE marketing_events (
    event_id TEXT PRIMARY KEY,
    pixel_id VARCHAR(32) NOT NULL,
    event_name VARCHAR(32) NOT NULL,
    consent_id UUID NOT NULL REFERENCES marketing_consents (id) ON DELETE CASCADE,
    order_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    page_url TEXT NOT NULL,
    properties JSONB NOT NULL,
    ttp VARCHAR(512),
    ttclid VARCHAR(512),
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'REJECTED', 'SKIPPED')),
    attempts INT NOT NULL DEFAULT 0,
    due_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    claim_id UUID
);
CREATE UNIQUE INDEX ux_marketing_order_events ON marketing_events (pixel_id, event_name, order_id)
    WHERE order_id IS NOT NULL;
CREATE INDEX ix_marketing_events_due ON marketing_events (pixel_id, due_at)
    WHERE status IN ('PENDING', 'SENDING');
CREATE INDEX ix_marketing_events_consent ON marketing_events (consent_id);
CREATE INDEX ix_marketing_events_retention ON marketing_events (occurred_at);

DO $$
BEGIN
    IF '${runtimeRole}' <> current_user AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${runtimeRole}') THEN
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE marketing_consents, marketing_events TO %I', '${runtimeRole}');
    END IF;
END $$;
