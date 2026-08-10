package com.harudle.diary.presentation;

import com.harudle.generation.presentation.GenerationUsageResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateDiaryResponse(
        UUID id,
        LocalDate diaryDate,
        String sourceText,
        OffsetDateTime createdAt,
        DiaryGenerationResponse generation,
        GenerationUsageResponse usage
) {
}
