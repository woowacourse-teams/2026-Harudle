package com.harudle.diary.presentation;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record GuestDiaryResponse(
        UUID id,
        LocalDate diaryDate,
        String sourceText,
        OffsetDateTime createdAt,
        DiaryGenerationResponse generation
) {
}
