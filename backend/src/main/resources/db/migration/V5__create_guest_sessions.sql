CREATE TABLE guest_sessions (
    id UUID NOT NULL,
    guest_user_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL,
    diary_id UUID,
    used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_guest_sessions
        PRIMARY KEY (id),

    CONSTRAINT uq_guest_sessions_guest_user
        UNIQUE (guest_user_id),

    CONSTRAINT uq_guest_sessions_token_hash
        UNIQUE (token_hash),

    CONSTRAINT uq_guest_sessions_diary
        UNIQUE (diary_id),

    CONSTRAINT ck_guest_sessions_token_hash
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),

    CONSTRAINT ck_guest_sessions_expiration
        CHECK (expires_at > created_at),

    CONSTRAINT fk_guest_sessions_guest_user
        FOREIGN KEY (guest_user_id)
        REFERENCES users (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_guest_sessions_diary
        FOREIGN KEY (diary_id)
        REFERENCES diaries (id)
        ON DELETE SET NULL
);

CREATE INDEX idx_guest_sessions_expires_at
    ON guest_sessions (expires_at);
