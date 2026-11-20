ALTER TABLE addresses ADD COLUMN neighborhood VARCHAR(160);
ALTER TABLE order_addresses ADD COLUMN neighborhood VARCHAR(160);

CREATE TABLE order_returns (
    order_id UUID PRIMARY KEY REFERENCES orders(id),
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('RETURN','EXCHANGE')),
    items VARCHAR(500) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED','APPROVED','REJECTED','COMPLETED')),
    response VARCHAR(500),
    reviewed_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_invoices (
    order_id UUID PRIMARY KEY REFERENCES orders(id),
    tax_id VARCHAR(13) NOT NULL,
    legal_name VARCHAR(254) NOT NULL,
    postal_code VARCHAR(5) NOT NULL,
    tax_regime VARCHAR(3) NOT NULL,
    cfdi_use VARCHAR(3) NOT NULL,
    fiscal_id UUID UNIQUE,
    pdf BYTEA,
    xml BYTEA,
    issued_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    issued_at TIMESTAMPTZ,
    CHECK ((issued_at IS NULL AND fiscal_id IS NULL AND pdf IS NULL AND xml IS NULL)
        OR (issued_at IS NOT NULL AND fiscal_id IS NOT NULL AND pdf IS NOT NULL AND xml IS NOT NULL))
);

CREATE TABLE shipping_labels (
    order_id UUID PRIMARY KEY REFERENCES orders(id),
    request_id UUID NOT NULL UNIQUE,
    live BOOLEAN NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('PROCESSING','READY','REVIEW','ABANDONED')),
    carrier VARCHAR(80) NOT NULL,
    service VARCHAR(100) NOT NULL,
    shipment_id VARCHAR(100),
    label_url VARCHAR(2048),
    tracking_number VARCHAR(120),
    tracking_url VARCHAR(2048),
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_order_returns_requested ON order_returns(created_at) WHERE status='REQUESTED';
CREATE INDEX ix_order_invoices_open ON order_invoices(created_at) WHERE issued_at IS NULL;
CREATE INDEX ix_orders_preparation_queue ON orders(placed_at) WHERE status='PAID' AND fulfillment_status='UNFULFILLED';

DO $$
BEGIN
    IF '${runtimeRole}' <> current_user AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${runtimeRole}') THEN
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE order_returns, order_invoices, shipping_labels TO %I',
                       '${runtimeRole}');
    END IF;
END $$;
