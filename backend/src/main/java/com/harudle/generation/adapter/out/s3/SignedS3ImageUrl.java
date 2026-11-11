package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

record SignedS3ImageUrl(ImageAccessUrl accessUrl, boolean credentialExpirationKnown) {

    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(1);
    private static final Duration CLOCK_SKEW_ALLOWANCE = Duration.ofSeconds(5);
    private static final Duration MINIMUM_REUSABLE_LIFETIME = REFRESH_MARGIN.plus(CLOCK_SKEW_ALLOWANCE);

    SignedS3ImageUrl {
        Objects.requireNonNull(accessUrl, "서명한 S3 이미지 주소가 필요합니다.");
    }

    boolean canReuseAt(Instant now) {
        return credentialExpirationKnown && accessUrl.expiresAt().isAfter(now.plus(MINIMUM_REUSABLE_LIFETIME));
    }

    static boolean supportsCache(Duration ttl) {
        return ttl.compareTo(MINIMUM_REUSABLE_LIFETIME) > 0;
    }

    boolean isValidAt(Instant now) {
        return credentialExpirationKnown && accessUrl.expiresAt().isAfter(now.plus(CLOCK_SKEW_ALLOWANCE));
    }

    boolean canServeAt(Instant now) {
        return credentialExpirationKnown ? canReuseAt(now) : accessUrl.expiresAt().isAfter(now);
    }
}
