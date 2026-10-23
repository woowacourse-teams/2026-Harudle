package com.harudle.generation.diary.service.dto;

import java.time.Instant;
import java.util.List;

public record ImageRecoveryResult(
        String status,
        String environment,
        String s3Bucket,
        String r2Bucket,
        String imageObjectKey,
        String originalKey,
        String mime,
        long size,
        String sha256,
        List<String> missingKeys,
        boolean originalRestored,
        boolean variantsRestored,
        Instant checkedAt
) {
    public ImageRecoveryResult {
        missingKeys = List.copyOf(missingKeys);
    }
}
