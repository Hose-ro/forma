ALTER TABLE users
    ALTER COLUMN password_hash DROP NOT NULL,
    ADD COLUMN google_subject VARCHAR(255),
    ADD CONSTRAINT users_google_subject_unique UNIQUE (google_subject),
    ADD CONSTRAINT users_have_credentials CHECK (password_hash IS NOT NULL OR google_subject IS NOT NULL),
    ADD CONSTRAINT users_google_customer_only CHECK (google_subject IS NULL OR role = 'CUSTOMER');
