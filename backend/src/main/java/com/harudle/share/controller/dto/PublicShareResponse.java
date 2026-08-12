package com.harudle.share.controller.dto;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record PublicShareResponse(
        String title,
        LocalDate diaryDate,
        URI imageUrl,
        OffsetDateTime imageUrlExpiresAt,
        OffsetDateTime createdAt
) {
}
