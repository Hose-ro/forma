CREATE TABLE categories (
    id           UUID PRIMARY KEY,
    slug         VARCHAR(120) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name         VARCHAR(120) NOT NULL,
    description  VARCHAR(500),
    image_url    VARCHAR(500),
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id                UUID PRIMARY KEY,
    category_id       UUID          NOT NULL REFERENCES categories (id) ON DELETE RESTRICT,
    slug              VARCHAR(160)  NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name              VARCHAR(200)  NOT NULL,
    description       TEXT,
    material          VARCHAR(500),
    status            VARCHAR(20)   NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'ARCHIVED')),
    featured          BOOLEAN       NOT NULL DEFAULT FALSE,
    price             NUMERIC(19,4) NOT NULL CHECK (price >= 0),
    compare_at_price  NUMERIC(19,4) CHECK (compare_at_price IS NULL OR compare_at_price >= 0),
    currency          CHAR(3)       NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_products_category ON products (category_id);
CREATE INDEX ix_products_status_featured ON products (status, featured DESC, created_at DESC);
CREATE INDEX ix_products_status_price ON products (status, price);
CREATE INDEX ix_products_name_lower ON products (lower(name));

CREATE TABLE product_variants (
    id                UUID PRIMARY KEY,
    product_id        UUID          NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    sku               VARCHAR(64)   NOT NULL UNIQUE,
    name              VARCHAR(120)  NOT NULL,
    color             VARCHAR(20),
    price             NUMERIC(19,4) NOT NULL CHECK (price >= 0),
    compare_at_price  NUMERIC(19,4) CHECK (compare_at_price IS NULL OR compare_at_price >= 0),
    currency          CHAR(3)       NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    stock             INT           NOT NULL DEFAULT 0 CHECK (stock >= 0),
    active            BOOLEAN       NOT NULL DEFAULT TRUE,
    position          INT           NOT NULL DEFAULT 0,
    image_url         VARCHAR(500),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_variants_product ON product_variants (product_id);

CREATE TABLE product_images (
    id          UUID PRIMARY KEY,
    product_id  UUID         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    url         VARCHAR(500) NOT NULL,
    alt_text    VARCHAR(200),
    position    INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_images_product ON product_images (product_id, position);
