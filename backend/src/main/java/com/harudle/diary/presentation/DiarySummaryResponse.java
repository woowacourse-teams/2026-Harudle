package com.harudle.diary.presentation;

import java.util.UUID;

public record DiarySummaryResponse(
        UUID id,
        String title,
        String thumbnailUrl
) {
}
