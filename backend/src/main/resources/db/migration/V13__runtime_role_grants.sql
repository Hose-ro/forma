-- V10 and V11 only granted their tables to a role literally named olvenio_runtime. Grant them to the role the
-- application actually connects with (DATABASE_USERNAME, e.g. forma_runtime on the staging created before the
-- rename), so the mail outboxes and account tokens work whatever the runtime role is called.
-- Skipped when the runtime role is the migration owner itself (local development) or does not exist.
DO $$
BEGIN
    IF '${runtimeRole}' <> current_user AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${runtimeRole}') THEN
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE order_emails, account_tokens, account_emails TO %I',
                       '${runtimeRole}');
    END IF;
END $$;
