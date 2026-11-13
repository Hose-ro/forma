-- Guest carts/orders are looked up by the keyed hash of the guest cookie.
CREATE INDEX ix_carts_token_hash ON carts (token_hash) WHERE token_hash IS NOT NULL;
CREATE INDEX ix_orders_access_token ON orders (access_token_hash, created_at DESC);
