package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

record SignedS3ImageUrl(ImageAccessUrl accessUrl, boolean credentialExpirationKnown) {

    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(1);
    private static final Duration CLOCK_SKEW_ALLOWANCE = Duration.ofSeconds(5);

    SignedS3ImageUrl {
        Objects.requireNonNull(accessUrl, "서명한 S3 이미지 주소가 필요합니다.");
    }

    boolean canReuseAt(Instant now) {
        return credentialExpirationKnown
                && accessUrl.expiresAt().isAfter(now.plus(REFRESH_MARGIN).plus(CLOCK_SKEW_ALLOWANCE));
    }

    boolean isValidAt(Instant now) {
        return credentialExpirationKnown && accessUrl.expiresAt().isAfter(now.plus(CLOCK_SKEW_ALLOWANCE));
    }

    boolean canServeAt(Instant now) {
        return credentialExpirationKnown ? canReuseAt(now) : accessUrl.expiresAt().isAfter(now);
    }
}
