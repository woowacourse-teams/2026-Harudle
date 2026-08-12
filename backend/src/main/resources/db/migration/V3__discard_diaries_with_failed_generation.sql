UPDATE diaries AS diary
SET deleted_at = COALESCE(generation.completed_at, generation.updated_at),
    updated_at = CURRENT_TIMESTAMP
FROM comic_generations AS generation
WHERE generation.diary_id = diary.id
  AND generation.status = 'FAILED'
  AND diary.deleted_at IS NULL;
