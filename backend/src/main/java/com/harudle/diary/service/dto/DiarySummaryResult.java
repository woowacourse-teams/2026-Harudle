package com.harudle.diary.service.dto;

import java.util.UUID;

public record DiarySummaryResult(
        UUID id,
        String title,
        String imageObjectKey
) {
}
