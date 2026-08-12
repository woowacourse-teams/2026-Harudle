package com.harudle.share.repository;

import java.time.Instant;
import java.time.LocalDate;

public record PublicShareSnapshot(
        String title,
        LocalDate diaryDate,
        String imageObjectKey,
        Instant diaryCreatedAt
) {
}
