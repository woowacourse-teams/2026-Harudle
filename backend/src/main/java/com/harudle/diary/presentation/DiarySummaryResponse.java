package com.harudle.diary.presentation;

import java.util.UUID;

public record DiarySummaryResponse(
        UUID diaryId,
        String title,
        String thumbnailUrl
) {
}
