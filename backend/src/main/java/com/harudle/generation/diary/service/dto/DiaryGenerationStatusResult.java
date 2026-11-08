package com.harudle.generation.diary.service.dto;

import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record DiaryGenerationStatusResult(
        UUID id,
        GenerationStatus status,
        @Nullable UUID diaryId,
        @Nullable GenerationErrorCode errorCode
) {
}
