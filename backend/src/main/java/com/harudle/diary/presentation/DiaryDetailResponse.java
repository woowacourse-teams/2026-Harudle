package com.harudle.diary.presentation;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DiaryDetailResponse(
        UUID id,
        LocalDate diaryDate,
        String sourceText,
        OffsetDateTime createdAt,
        DiaryGenerationResponse generation
) {
}
