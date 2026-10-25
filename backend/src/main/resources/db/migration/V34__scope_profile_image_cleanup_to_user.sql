ALTER TABLE profile_image_cleanup_tasks
    RENAME COLUMN next_attempt_at TO delete_after;

DROP INDEX idx_profile_image_cleanup_tasks_next_attempt;

CREATE INDEX idx_profile_image_cleanup_tasks_user_delete_after
    ON profile_image_cleanup_tasks (user_id, delete_after, object_key);
