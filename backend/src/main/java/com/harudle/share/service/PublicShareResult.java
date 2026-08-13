package com.harudle.share.service;

import java.time.Instant;
import java.time.LocalDate;

public record PublicShareResult(
        String title,
        LocalDate diaryDate,
        String imageObjectKey,
        Instant createdAt
) {
}
