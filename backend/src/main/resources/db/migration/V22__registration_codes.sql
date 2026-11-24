ALTER TABLE account_emails DROP CONSTRAINT account_emails_purpose_check;
ALTER TABLE account_emails ADD CONSTRAINT account_emails_purpose_check
    CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION', 'REGISTRATION_CODE'));

CREATE TABLE pending_registrations (
    id UUID PRIMARY KEY REFERENCES account_emails(id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone VARCHAR(30),
    terms_accepted_at TIMESTAMPTZ NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5)
);

DO $$
BEGIN
    IF '${runtimeRole}' <> current_user AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${runtimeRole}') THEN
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE pending_registrations TO %I', '${runtimeRole}');
    END IF;
END $$;
