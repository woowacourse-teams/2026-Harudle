CREATE TABLE apple_oauth_tokens (
    oauth_account_id BIGINT NOT NULL REFERENCES oauth_accounts (id) ON DELETE CASCADE,
    client_id VARCHAR(255) NOT NULL,
    identity_token TEXT NOT NULL,
    access_token TEXT NOT NULL,
    refresh_token TEXT,
    access_expires_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (oauth_account_id, client_id)
);
