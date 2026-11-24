-- Run with psql as a local administrator against a disposable olvenio_audit_* database after V23.
-- Set -v runtime_role=<role> when the deployment uses a different role name.
\set ON_ERROR_STOP on
\if :{?runtime_role}
\else
    \set runtime_role olvenio_runtime
\endif

BEGIN;
DO $$
BEGIN
    IF current_database() !~ '^olvenio_audit_' THEN
        RAISE EXCEPTION 'Use a disposable olvenio_audit_* database';
    END IF;
END $$;

SET LOCAL ROLE :"runtime_role";

DO $$
DECLARE
    expected record;
    privilege text;
BEGIN
    FOR expected IN SELECT * FROM (VALUES
        ('users', 'SELECT INSERT UPDATE'),
        ('orders', 'SELECT INSERT UPDATE'),
        ('order_events', 'SELECT INSERT'),
        ('audit_logs', 'SELECT INSERT'),
        ('payment_events', 'SELECT INSERT'),
        ('addresses', 'SELECT INSERT UPDATE DELETE'),
        ('order_emails', 'SELECT INSERT UPDATE DELETE'),
        ('account_tokens', 'SELECT INSERT UPDATE DELETE'),
        ('account_emails', 'SELECT INSERT UPDATE DELETE'),
        ('order_returns', 'SELECT INSERT UPDATE'),
        ('order_invoices', 'SELECT INSERT UPDATE'),
        ('shipping_labels', 'SELECT INSERT UPDATE'),
        ('user_category_interests', 'SELECT INSERT DELETE'),
        ('home_heroes', 'SELECT UPDATE'),
        ('product_favorites', 'SELECT INSERT DELETE'),
        ('pending_registrations', 'SELECT INSERT UPDATE DELETE')
    ) AS grants(table_name, privileges)
    LOOP
        FOREACH privilege IN ARRAY string_to_array(expected.privileges, ' ') LOOP
            IF NOT has_table_privilege(current_user, expected.table_name, privilege) THEN
                RAISE EXCEPTION 'Missing % on % for %', privilege, expected.table_name, current_user;
            END IF;
        END LOOP;
    END LOOP;
    FOR expected IN SELECT * FROM (VALUES
        ('users', 'DELETE, TRUNCATE'), ('orders', 'DELETE, TRUNCATE'),
        ('audit_logs', 'UPDATE, DELETE, TRUNCATE'), ('order_events', 'UPDATE, DELETE, TRUNCATE'),
        ('payment_events', 'UPDATE, DELETE, TRUNCATE')
    ) AS revokes(table_name, privileges)
    LOOP
        IF has_table_privilege(current_user, expected.table_name, expected.privileges) THEN
            RAISE EXCEPTION 'Unexpected protected privileges on % for %', expected.table_name, current_user;
        END IF;
    END LOOP;
    IF NOT has_sequence_privilege(current_user, 'order_number_seq', 'USAGE') THEN
        RAISE EXCEPTION 'Runtime cannot use the order number sequence';
    END IF;
END $$;

INSERT INTO users (id, email, password_hash, google_subject, first_name, last_name, role) VALUES
    ('10000000-0000-0000-0000-000000000001', 'order-actor@example.test', 'fixture', NULL, 'Order', 'Actor', 'ADMIN'),
    ('10000000-0000-0000-0000-000000000002', 'audit-actor@example.test', 'fixture', NULL, 'Audit', 'Actor', 'ADMIN'),
    ('10000000-0000-0000-0000-000000000003', 'customer@example.test', NULL, 'test-subject', 'Local', 'Customer', 'CUSTOMER');
UPDATE users SET phone = '5550100' WHERE google_subject = 'test-subject';

INSERT INTO orders (id, user_id, status, email, customer_first_name, customer_last_name, currency,
                    subtotal, shipping_total, total, access_token_hash, quote_expires_at)
VALUES ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000003',
        'DRAFT', 'customer@example.test', 'Local', 'Customer', 'MXN', 100, 0, 100, repeat('0', 64), now());
UPDATE orders SET status = 'CANCELLED' WHERE id = '20000000-0000-0000-0000-000000000001';

INSERT INTO order_events (id, order_id, type, actor_user_id)
VALUES ('30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
        'CANCELLED', '10000000-0000-0000-0000-000000000001');
INSERT INTO audit_logs (id, actor_user_id, action, entity_type)
VALUES ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'FIXTURE', 'fixture');

DO $$
BEGIN
    BEGIN
        DELETE FROM users WHERE id = '10000000-0000-0000-0000-000000000003';
        RAISE EXCEPTION 'Runtime can delete users';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
    BEGIN
        DELETE FROM orders WHERE id = '20000000-0000-0000-0000-000000000001';
        RAISE EXCEPTION 'Runtime can delete orders';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
END $$;

RESET ROLE;

DO $$
BEGIN
    BEGIN
        DELETE FROM orders WHERE id = '20000000-0000-0000-0000-000000000001';
        RAISE EXCEPTION 'The historical order reference did not prevent deletion';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
    BEGIN
        DELETE FROM users WHERE id = '10000000-0000-0000-0000-000000000001';
        RAISE EXCEPTION 'The order-event actor reference did not prevent deletion';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
    BEGIN
        DELETE FROM users WHERE id = '10000000-0000-0000-0000-000000000002';
        RAISE EXCEPTION 'The audit-log actor reference did not prevent deletion';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
END $$;

ROLLBACK;
