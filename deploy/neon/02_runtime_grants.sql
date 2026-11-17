-- Run as the Flyway/migration owner after the first staging startup has applied V1..V10 (V10 also grants its own tables when the runtime role exists).
BEGIN;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    users, addresses, categories, products, product_variants, product_images,
    carts, cart_items, orders, order_items, order_addresses,
    payments, payment_attempts, payment_resources, order_emails, account_tokens
TO olvenio_runtime;
GRANT SELECT, INSERT ON TABLE audit_logs, order_events, payment_events TO olvenio_runtime;
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE audit_logs, order_events, payment_events FROM olvenio_runtime;
REVOKE ALL ON TABLE flyway_schema_history FROM olvenio_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO olvenio_runtime;
COMMIT;
