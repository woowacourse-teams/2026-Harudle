ALTER TABLE diary_generations
    ADD COLUMN requested_by_user_id UUID;

UPDATE diary_generations AS generation
SET requested_by_user_id = diary.user_id
FROM diaries AS diary
WHERE diary.id = generation.diary_id;

ALTER TABLE diary_generations
    ALTER COLUMN requested_by_user_id SET NOT NULL;
