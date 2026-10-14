ALTER TABLE diary_generations
    ADD COLUMN storyboard_provider TEXT,
    ADD COLUMN storyboard_model TEXT;

ALTER TABLE diary_generations
    ADD CONSTRAINT ck_diary_generations_storyboard_provider_info
        CHECK (
            (storyboard_provider IS NULL AND storyboard_model IS NULL)
            OR (
                storyboard_provider IS NOT NULL AND storyboard_model IS NOT NULL
                AND btrim(storyboard_provider) <> '' AND btrim(storyboard_model) <> ''
            )
        );
