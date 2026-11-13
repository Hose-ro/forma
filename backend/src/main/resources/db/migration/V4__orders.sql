CREATE SEQUENCE order_number_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE orders (
    id                   UUID PRIMARY KEY,
    order_number         VARCHAR(32)   UNIQUE,
    user_id              UUID          REFERENCES users (id) ON DELETE SET NULL,
    cart_id              UUID          REFERENCES carts (id) ON DELETE SET NULL,
    status               VARCHAR(20)   NOT NULL CHECK (status IN ('DRAFT', 'PENDING_PAYMENT', 'CANCELLED')),
    email                VARCHAR(320)  NOT NULL,
    customer_first_name  VARCHAR(100)  NOT NULL,
    customer_last_name   VARCHAR(100)  NOT NULL,
    customer_phone       VARCHAR(30),
    currency             CHAR(3)       NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    subtotal             NUMERIC(19,4) NOT NULL CHECK (subtotal >= 0),
    shipping_total       NUMERIC(19,4) NOT NULL CHECK (shipping_total >= 0),
    total                NUMERIC(19,4) NOT NULL CHECK (total >= 0),
    access_token_hash    VARCHAR(64)   NOT NULL,
    idempotency_key      VARCHAR(100)  UNIQUE,
    quote_expires_at     TIMESTAMPTZ   NOT NULL,
    placed_at            TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_orders_placed CHECK (status = 'DRAFT' OR status = 'CANCELLED' OR (order_number IS NOT NULL AND placed_at IS NOT NULL))
);
CREATE INDEX ix_orders_user ON orders (user_id, created_at DESC);
CREATE INDEX ix_orders_status_created ON orders (status, created_at DESC);

CREATE TABLE order_items (
    id                UUID PRIMARY KEY,
    order_id          UUID          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id        UUID          REFERENCES products (id) ON DELETE SET NULL,
    variant_id        UUID          REFERENCES product_variants (id) ON DELETE SET NULL,
    sku               VARCHAR(64)   NOT NULL,
    product_name      VARCHAR(200)  NOT NULL,
    product_slug      VARCHAR(160)  NOT NULL,
    variant_name      VARCHAR(120)  NOT NULL,
    image_url         VARCHAR(500),
    unit_price        NUMERIC(19,4) NOT NULL CHECK (unit_price >= 0),
    quantity          INT           NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    line_total        NUMERIC(19,4) NOT NULL CHECK (line_total >= 0),
    currency          CHAR(3)       NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    position          INT           NOT NULL DEFAULT 0
);
CREATE INDEX ix_order_items_order ON order_items (order_id);
CREATE INDEX ix_order_items_variant ON order_items (variant_id);
CREATE INDEX ix_order_items_product ON order_items (product_id);

CREATE TABLE order_addresses (
    id              UUID PRIMARY KEY,
    order_id        UUID         NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    type            VARCHAR(20)  NOT NULL CHECK (type IN ('SHIPPING', 'BILLING')),
    recipient_name  VARCHAR(200) NOT NULL,
    line1           VARCHAR(200) NOT NULL,
    line2           VARCHAR(200),
    city            VARCHAR(100) NOT NULL,
    state           VARCHAR(100) NOT NULL,
    postal_code     VARCHAR(20)  NOT NULL,
    country         CHAR(2)      NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
    phone           VARCHAR(30),
    CONSTRAINT ux_order_addresses_type UNIQUE (order_id, type)
);

CREATE TABLE order_events (
    id             UUID PRIMARY KEY,
    order_id       UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    type           VARCHAR(30) NOT NULL,
    from_status    VARCHAR(20),
    to_status      VARCHAR(20),
    note           VARCHAR(500),
    actor_user_id  UUID        REFERENCES users (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_order_events_order ON order_events (order_id, created_at);
