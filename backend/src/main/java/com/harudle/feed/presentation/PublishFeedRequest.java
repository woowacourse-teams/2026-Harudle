package com.harudle.feed.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record PublishFeedRequest(
        @NotNull @Schema(description = "생성 완료된 본인 일기 UUID") UUID diaryId,
        @NotNull @Positive @Schema(description = "게시할 활성 카테고리 ID") Long categoryId
) {
}
