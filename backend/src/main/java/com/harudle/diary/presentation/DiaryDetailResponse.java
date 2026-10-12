package com.harudle.diary.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record DiaryDetailResponse(
        UUID id,
        @Schema(description = "일기 날짜 (YYYY-MM-DD)")
        LocalDate diaryDate,
        String sourceText,
        @Schema(description = "일기 생성 시각 (RFC 3339)")
        OffsetDateTime createdAt,
        DiaryGenerationResponse generation,
        @Schema(description = "연결된 활성 피드 ID. 게시 전 또는 피드 삭제 후에는 null", type = "string", format = "uuid")
        @Nullable UUID publishedFeedId
) {
}
