package com.harudle.diary.service.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DiaryDetailResult(
        UUID id,
        LocalDate diaryDate,
        String sourceText,
        Instant createdAt,
        DiaryGenerationResult generation
) {
}
