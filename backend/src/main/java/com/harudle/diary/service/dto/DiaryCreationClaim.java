package com.harudle.diary.service.dto;

import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.GenerationUsage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DiaryCreationClaim(
        UUID diaryId,
        LocalDate diaryDate,
        String sourceText,
        Instant createdAt,
        UUID generationId,
        GenerationStatus generationStatus,
        String title,
        String imageObjectKey,
        Instant completedAt,
        GenerationErrorCode errorCode,
        GenerationUsage usage,
        boolean newlyCreated
) {
}
