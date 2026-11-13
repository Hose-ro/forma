-- Run as the same role used by Flyway, in a dedicated staging database.
-- Create forma_runtime with SQL and a private password first (not the admin-role Console/API flow). No password belongs in this file.
BEGIN;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'forma_runtime') THEN
        RAISE EXCEPTION 'Create forma_runtime with SQL before running bootstrap';
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO forma_runtime', current_database());
END $$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO forma_runtime;
-- New tables start append-only for runtime until the explicit grants in 02.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT ON TABLES TO forma_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO forma_runtime;
COMMIT;
