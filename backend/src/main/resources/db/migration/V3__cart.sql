CREATE TABLE carts (
    id          UUID PRIMARY KEY,
    user_id     UUID        REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64),
    currency    CHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_carts_owner CHECK (user_id IS NOT NULL OR token_hash IS NOT NULL)
);
CREATE UNIQUE INDEX ux_carts_user ON carts (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ix_carts_updated ON carts (updated_at);

CREATE TABLE cart_items (
    id          UUID PRIMARY KEY,
    cart_id     UUID        NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    variant_id  UUID        NOT NULL REFERENCES product_variants (id) ON DELETE CASCADE,
    quantity    INT         NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_cart_items_variant UNIQUE (cart_id, variant_id)
);
CREATE INDEX ix_cart_items_variant ON cart_items (variant_id);
