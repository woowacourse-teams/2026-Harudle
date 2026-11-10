package com.harudle.campus.application;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** 캠퍼스에 알려 주는 회원 정보와 토큰 수명. 이메일 · 실명 · 하루들 토큰은 담지 않는다. */
public record CampusLinkClaims(
        UUID userId,
        String nickname,
        @Nullable URI profileImageUrl,
        String state,
        Instant issuedAt,
        Instant expiresAt
) {
}
