CREATE INDEX idx_diary_generations_created_at_id
    ON diary_generations (created_at DESC, id DESC);
