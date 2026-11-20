CREATE TABLE user_category_interests (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, category_id)
);
CREATE INDEX ix_category_interests_category ON user_category_interests(category_id);

DO $$
BEGIN
    IF '${runtimeRole}' <> current_user AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${runtimeRole}') THEN
        EXECUTE format('GRANT SELECT, INSERT, DELETE ON TABLE user_category_interests TO %I', '${runtimeRole}');
    END IF;
END $$;
