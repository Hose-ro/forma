CREATE TABLE audit_logs (
    id             UUID PRIMARY KEY,
    actor_user_id  UUID         REFERENCES users (id) ON DELETE SET NULL,
    action         VARCHAR(60)  NOT NULL,
    entity_type    VARCHAR(60)  NOT NULL,
    entity_id      UUID,
    details        JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX ix_audit_logs_created ON audit_logs (created_at DESC);
