-- Run as the Flyway/migration owner after the first staging startup has applied V1..V8.
BEGIN;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    users, addresses, categories, products, product_variants, product_images,
    carts, cart_items, orders, order_items, order_addresses,
    payments, payment_attempts, payment_resources
TO forma_runtime;
GRANT SELECT, INSERT ON TABLE audit_logs, order_events, payment_events TO forma_runtime;
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE audit_logs, order_events, payment_events FROM forma_runtime;
REVOKE ALL ON TABLE flyway_schema_history FROM forma_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO forma_runtime;
COMMIT;
