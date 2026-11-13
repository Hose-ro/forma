ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check CHECK (status IN ('DRAFT','PENDING_PAYMENT','PAID','PAYMENT_REVIEW','REFUNDED','PARTIALLY_REFUNDED','CANCELLED'));
ALTER TABLE orders ADD COLUMN payment_status VARCHAR(24) NOT NULL DEFAULT 'CREATED';
CREATE TABLE payments (
 id UUID PRIMARY KEY, order_id UUID NOT NULL UNIQUE REFERENCES orders(id),
 provider VARCHAR(24) NOT NULL DEFAULT 'MERCADO_PAGO' CHECK (provider = 'MERCADO_PAGO'),
 provider_payment_id VARCHAR(100) UNIQUE, provider_preference_id VARCHAR(200) UNIQUE,
 status VARCHAR(24) NOT NULL CHECK (status IN ('CREATED','PENDING','IN_PROCESS','APPROVED','REJECTED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED','UNKNOWN')),
 amount NUMERIC(19,4) NOT NULL CHECK (amount > 0), currency VARCHAR(3) NOT NULL,
 checkout_url TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 approved_at TIMESTAMPTZ, failure_reason VARCHAR(100), reconciliation_count INT NOT NULL DEFAULT 0,
 next_reconciliation_at TIMESTAMPTZ NOT NULL DEFAULT now(), lease_until TIMESTAMPTZ
);
CREATE INDEX ix_payments_reconcile ON payments(next_reconciliation_at);
CREATE TABLE payment_attempts (
 id UUID PRIMARY KEY, payment_id UUID NOT NULL UNIQUE REFERENCES payments(id),
 idempotency_key VARCHAR(100) NOT NULL UNIQUE, provider_request_id VARCHAR(200),
 status VARCHAR(24) NOT NULL CHECK (status IN ('CREATING','READY','UNKNOWN')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE payment_events (
 id UUID PRIMARY KEY, payment_id UUID REFERENCES payments(id), provider_event_id VARCHAR(250) UNIQUE,
 event_type VARCHAR(40) NOT NULL, payload JSONB NOT NULL DEFAULT '{}',
 processing_status VARCHAR(24) NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT now(), processed_at TIMESTAMPTZ
);
CREATE INDEX ix_payment_events_payment ON payment_events(payment_id,received_at);
-- One Checkout Pro preference can produce several payment resources (e.g. a declined card then a retry).
CREATE TABLE payment_resources (
 provider_payment_id VARCHAR(100) PRIMARY KEY, payment_id UUID NOT NULL REFERENCES payments(id),
 status VARCHAR(24) NOT NULL, provider_status VARCHAR(50) NOT NULL,
 provider_updated_at TIMESTAMPTZ NOT NULL, refunded_amount NUMERIC(19,4) NOT NULL DEFAULT 0
);
CREATE INDEX ix_payment_resources_payment ON payment_resources(payment_id);
