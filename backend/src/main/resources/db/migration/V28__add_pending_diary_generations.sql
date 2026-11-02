ALTER TABLE diary_generations
    DROP CONSTRAINT ck_diary_generations_status,
    ADD CONSTRAINT ck_diary_generations_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED'));

CREATE INDEX idx_diary_generations_pending_created_at_id
    ON diary_generations (created_at, id)
    WHERE status = 'PENDING';
