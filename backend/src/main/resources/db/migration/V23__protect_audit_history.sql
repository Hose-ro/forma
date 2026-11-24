ALTER TABLE order_events
    DROP CONSTRAINT order_events_order_id_fkey,
    DROP CONSTRAINT order_events_actor_user_id_fkey,
    ADD CONSTRAINT order_events_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE NO ACTION,
    ADD CONSTRAINT order_events_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE NO ACTION;

ALTER TABLE audit_logs
    DROP CONSTRAINT audit_logs_actor_user_id_fkey,
    ADD CONSTRAINT audit_logs_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE NO ACTION;

DO $$
DECLARE
    runtime_role name := '${runtimeRole}';
BEGIN
    IF runtime_role <> current_user AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
        EXECUTE format('REVOKE DELETE, TRUNCATE ON TABLE users, orders FROM %I', runtime_role);
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON TABLE audit_logs, order_events, payment_events FROM %I', runtime_role);
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
    END IF;
END $$;
