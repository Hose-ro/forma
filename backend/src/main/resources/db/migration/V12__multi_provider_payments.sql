-- Several checkout attempts per order: a buyer may switch payment method on a pending order.
-- At most one checkout is open at a time; earlier ones are closed locally first and then at their provider.
ALTER TABLE payments DROP CONSTRAINT payments_provider_check;
ALTER TABLE payments ADD CONSTRAINT payments_provider_check
    CHECK (provider IN ('MERCADO_PAGO', 'STRIPE', 'PAYPAL', 'CONEKTA'));
ALTER TABLE payments ALTER COLUMN provider DROP DEFAULT;

ALTER TABLE payments DROP CONSTRAINT payments_order_id_key;
CREATE UNIQUE INDEX ux_payments_open_checkout ON payments (order_id) WHERE checkout_closed_at IS NULL;
CREATE INDEX ix_payments_order ON payments (order_id, created_at);

-- provider_preference_id holds the provider's checkout id: Mercado Pago preference, Stripe Checkout Session,
-- PayPal order or Conekta order. provider_closed_at records that the provider confirmed the closing, so a
-- checkout closed only locally (provider unreachable) blocks opening another one until the close succeeds.
COMMENT ON COLUMN payments.provider_preference_id IS 'Checkout id at the provider (preference, session or order)';
ALTER TABLE payments ADD COLUMN provider_closed_at TIMESTAMPTZ;
UPDATE payments p SET provider_closed_at = e.closed
    FROM (SELECT payment_id, max(received_at) AS closed FROM payment_events
          WHERE event_type = 'PREFERENCE_CLOSED' GROUP BY payment_id) e
    WHERE e.payment_id = p.id AND p.checkout_closed_at IS NOT NULL;
CREATE INDEX ix_payments_pending_close ON payments (checkout_closed_at)
    WHERE checkout_closed_at IS NOT NULL AND provider_closed_at IS NULL;

-- Why a checkout was closed. SWITCH: the buyer chose another method and the order stays payable.
-- ORDER: the order is being cancelled, expired or was paid; no new checkout may be opened for it.
ALTER TABLE payments ADD COLUMN close_reason VARCHAR(8) CHECK (close_reason IN ('SWITCH', 'ORDER'));
UPDATE payments SET close_reason = 'ORDER' WHERE checkout_closed_at IS NOT NULL;
ALTER TABLE payments ADD CONSTRAINT ck_payments_close_reason
    CHECK ((checkout_closed_at IS NULL) = (close_reason IS NULL));
