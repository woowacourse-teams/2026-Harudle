CREATE TABLE withdrawal_image_cleanup_tasks (
    storage VARCHAR(20) NOT NULL CHECK (storage IN ('S3_DIARY', 'S3_PROFILE', 'R2')),
    object_key VARCHAR(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (storage, object_key)
);
