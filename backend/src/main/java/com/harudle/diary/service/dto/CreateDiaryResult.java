package com.harudle.diary.service.dto;

import com.harudle.generation.usage.domain.GenerationUsage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CreateDiaryResult(
        UUID id,
        LocalDate diaryDate,
        String sourceText,
        Instant createdAt,
        DiaryGenerationResult generation,
        GenerationUsage usage,
        boolean newlyCreated
) {
}
