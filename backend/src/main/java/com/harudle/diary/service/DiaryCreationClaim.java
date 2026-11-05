package com.harudle.diary.service;

import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.service.exception.DiaryGenerationFailedException;
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
        boolean newlyCreated,
        GenerationTokenUsage tokenUsage
) {
    DiaryCreationClaim(
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
        this(diaryId, diaryDate, sourceText, createdAt, generationId, generationStatus,
                title, imageObjectKey, completedAt, errorCode, newlyCreated, null);
    }

    DiaryGenerationResult toGenerationResult() {
        return toGenerationResult(false);
    }

    DiaryGenerationResult toGenerationResult(boolean guest) {
        if (generationStatus == GenerationStatus.FAILED) {
            throw new DiaryGenerationFailedException(errorCode, guest);
        }
        return new DiaryGenerationResult(
                generationId, generationStatus, title, imageObjectKey, completedAt, tokenUsage
        );
    }
}
