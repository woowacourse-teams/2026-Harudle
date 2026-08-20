package com.harudle.diary.service;

import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record DiaryCreationClaim(
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
        boolean newlyCreated
) {
}
