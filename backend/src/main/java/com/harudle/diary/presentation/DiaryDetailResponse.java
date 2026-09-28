package com.harudle.diary.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DiaryDetailResponse(
        UUID id,
        @Schema(description = "일기 날짜 (YYYY-MM-DD)")
        LocalDate diaryDate,
        String sourceText,
        @Schema(description = "일기 생성 시각 (RFC 3339)")
        OffsetDateTime createdAt,
        DiaryGenerationResponse generation
) {
}
