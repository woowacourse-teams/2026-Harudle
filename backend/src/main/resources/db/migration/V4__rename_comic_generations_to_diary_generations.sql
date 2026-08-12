ALTER TABLE comic_generations
    RENAME TO diary_generations;

ALTER TABLE diary_generations
    RENAME CONSTRAINT pk_comic_generations
    TO pk_diary_generations;

ALTER TABLE diary_generations
    RENAME CONSTRAINT uq_comic_generations_diary
    TO uq_diary_generations_diary;

ALTER TABLE diary_generations
    RENAME CONSTRAINT uq_comic_generations_idempotency
    TO uq_diary_generations_idempotency;

ALTER TABLE diary_generations
    RENAME CONSTRAINT uq_comic_generations_image_key
    TO uq_diary_generations_image_key;

ALTER TABLE diary_generations
    RENAME CONSTRAINT ck_comic_generations_request_fingerprint
    TO ck_diary_generations_request_fingerprint;

ALTER TABLE diary_generations
    RENAME CONSTRAINT ck_comic_generations_image_key_length
    TO ck_diary_generations_image_key_length;

ALTER TABLE diary_generations
    RENAME CONSTRAINT ck_comic_generations_status
    TO ck_diary_generations_status;

ALTER TABLE diary_generations
    RENAME CONSTRAINT ck_comic_generations_succeeded_image_key
    TO ck_diary_generations_succeeded_image_key;

ALTER TABLE diary_generations
    RENAME CONSTRAINT fk_comic_generations_diary
    TO fk_diary_generations_diary;

ALTER TABLE diary_generations
    RENAME CONSTRAINT fk_comic_generations_prompt
    TO fk_diary_generations_prompt;

ALTER INDEX idx_comic_generations_prompt_id
    RENAME TO idx_diary_generations_prompt_id;
