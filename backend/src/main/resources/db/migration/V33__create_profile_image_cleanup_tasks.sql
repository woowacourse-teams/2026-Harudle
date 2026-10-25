CREATE TABLE profile_image_cleanup_tasks (
    object_key VARCHAR(1024) PRIMARY KEY,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_profile_image_cleanup_tasks_next_attempt
    ON profile_image_cleanup_tasks (next_attempt_at);
