CREATE TABLE admin_generation_usage_restores (
    idempotency_key UUID NOT NULL,
    user_id UUID NOT NULL,
    restore_count INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    usage_date DATE,
    used_count INTEGER,
    limit_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_admin_generation_usage_restores PRIMARY KEY (idempotency_key),
    CONSTRAINT ck_admin_generation_usage_restores_count
        CHECK (restore_count >= 1),
    CONSTRAINT ck_admin_generation_usage_restores_status
        CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'CONFLICT')),
    CONSTRAINT ck_admin_generation_usage_restores_result
        CHECK (
            (
                status = 'PROCESSING'
                AND usage_date IS NULL
                AND used_count IS NULL
                AND limit_count IS NULL
            )
            OR (
                status = 'SUCCEEDED'
                AND usage_date IS NOT NULL
                AND used_count IS NOT NULL
                AND limit_count IS NOT NULL
                AND used_count >= 0
                AND limit_count >= 1
                AND used_count <= limit_count
            )
            OR (
                status = 'CONFLICT'
                AND usage_date IS NULL
                AND used_count IS NULL
                AND limit_count IS NULL
            )
        ),
    CONSTRAINT fk_admin_generation_usage_restores_user
        FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE
);
