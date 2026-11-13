-- Existing quotes predate revision binding and must be quoted again. Historical orders stay intact.
ALTER TABLE carts ADD COLUMN checkout_revision BIGINT NOT NULL DEFAULT 0 CHECK (checkout_revision >= 0);
ALTER TABLE orders ADD COLUMN cart_revision BIGINT CHECK (cart_revision >= 0);
CREATE UNIQUE INDEX ux_orders_cart_revision ON orders(cart_id, cart_revision)
    WHERE placed_at IS NOT NULL AND cart_revision IS NOT NULL;

ALTER TABLE carts ADD CONSTRAINT ck_carts_single_owner
    CHECK ((user_id IS NULL) <> (token_hash IS NULL));
ALTER TABLE carts ADD CONSTRAINT ck_carts_token_hash
    CHECK (token_hash IS NULL OR token_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE orders ADD CONSTRAINT ck_orders_token_hash CHECK (access_token_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE orders ADD CONSTRAINT ck_orders_totals CHECK (total = subtotal + shipping_total);
-- Java uses HALF_EVEN; allow the half-cent boundary without imposing PostgreSQL's HALF_UP tie rule.
ALTER TABLE order_items ADD CONSTRAINT ck_order_items_total
    CHECK (line_total = round(line_total, 2) AND abs(line_total - unit_price * quantity) <= 0.005);
ALTER TABLE orders ADD CONSTRAINT ck_orders_payment_status
    CHECK (payment_status IN ('CREATED','PENDING','IN_PROCESS','APPROVED','REJECTED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED','UNKNOWN'));
ALTER TABLE payments ADD CONSTRAINT ck_payments_currency CHECK (currency ~ '^[A-Z]{3}$');
ALTER TABLE payment_resources ADD CONSTRAINT ck_payment_resources_refund CHECK (refunded_amount >= 0);
