-- Run as the Flyway/migration owner after Flyway applies the migrations through V23.
BEGIN;
GRANT SELECT, INSERT, UPDATE ON TABLE users, orders TO olvenio_runtime;
REVOKE DELETE, TRUNCATE ON TABLE users, orders FROM olvenio_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    addresses, categories, products, product_variants, product_images,
    carts, cart_items, order_items, order_addresses,
    payments, payment_attempts, payment_resources, order_emails, account_tokens, account_emails
TO olvenio_runtime;
GRANT SELECT, INSERT ON TABLE audit_logs, order_events, payment_events TO olvenio_runtime;
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE audit_logs, order_events, payment_events FROM olvenio_runtime;
REVOKE ALL ON TABLE flyway_schema_history FROM olvenio_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO olvenio_runtime;
DO $$
DECLARE
    runtime_role name := 'olvenio_runtime';
BEGIN
    IF EXISTS (
        SELECT 1 FROM (VALUES
            ('users', 'DELETE, TRUNCATE'), ('orders', 'DELETE, TRUNCATE'),
            ('audit_logs', 'UPDATE, DELETE, TRUNCATE'), ('order_events', 'UPDATE, DELETE, TRUNCATE'),
            ('payment_events', 'UPDATE, DELETE, TRUNCATE')
        ) AS protected(table_name, privileges)
        WHERE has_table_privilege(runtime_role, protected.table_name, protected.privileges)
    ) THEN
        RAISE EXCEPTION 'Runtime role % retains write privileges on protected tables. Review role memberships and PUBLIC grants.', runtime_role;
    END IF;
END $$;
COMMIT;
