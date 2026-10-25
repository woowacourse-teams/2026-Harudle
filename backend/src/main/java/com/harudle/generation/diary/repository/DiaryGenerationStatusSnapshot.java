package com.harudle.generation.diary.repository;

import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import java.util.UUID;

public record DiaryGenerationStatusSnapshot(
        UUID id,
        UUID diaryId,
        GenerationStatus status,
        GenerationErrorCode errorCode
) {
}
