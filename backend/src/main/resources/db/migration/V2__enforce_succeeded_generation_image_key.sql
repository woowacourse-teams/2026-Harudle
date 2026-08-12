ALTER TABLE comic_generations
    ADD CONSTRAINT ck_comic_generations_succeeded_image_key
        CHECK (
            status <> 'SUCCEEDED'
            OR image_object_key IS NOT NULL
        ) NOT VALID;

UPDATE comic_generations
SET status = 'FAILED',
    error_code = 'IMAGE_STORAGE_ERROR',
    completed_at = COALESCE(completed_at, updated_at, created_at),
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'SUCCEEDED'
  AND image_object_key IS NULL;

ALTER TABLE comic_generations
    VALIDATE CONSTRAINT ck_comic_generations_succeeded_image_key;
