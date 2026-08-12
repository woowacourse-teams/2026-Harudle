package com.harudle.generation.service.port;

import java.net.URI;
import java.time.Instant;

public record ImageAccessUrl(
        URI url,
        Instant expiresAt
) {

    public ImageAccessUrl {
        if (url == null || !url.isAbsolute()) {
            throw new IllegalArgumentException("이미지 접근 URL은 절대 URI여야 합니다.");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("이미지 접근 URL 만료 시각은 필수입니다.");
        }
    }
}
