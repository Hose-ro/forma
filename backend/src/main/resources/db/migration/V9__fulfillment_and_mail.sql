-- Logistics state of paid orders and the transactional-email outbox markers.
ALTER TABLE orders
    ADD COLUMN fulfillment_status VARCHAR(20) NOT NULL DEFAULT 'UNFULFILLED'
        CHECK (fulfillment_status IN ('UNFULFILLED', 'PREPARING', 'SHIPPED', 'DELIVERED')),
    ADD COLUMN carrier VARCHAR(80),
    ADD COLUMN tracking_number VARCHAR(120),
    ADD COLUMN shipped_at TIMESTAMPTZ,
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN confirmation_email_sent_at TIMESTAMPTZ,
    ADD COLUMN shipping_email_sent_at TIMESTAMPTZ;

-- Orders paid before email existed must never trigger a late confirmation.
UPDATE orders SET confirmation_email_sent_at = now() WHERE status = 'PAID';

CREATE INDEX ix_orders_mail_pending ON orders (placed_at)
    WHERE status = 'PAID' AND (confirmation_email_sent_at IS NULL OR shipping_email_sent_at IS NULL);
