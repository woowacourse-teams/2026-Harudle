package com.harudle.campus.application;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** 캠퍼스에 알려 주는 회원 정보. 이메일과 실명은 포함하지 않는다. */
public record CampusLinkProfile(
        UUID userId,
        String nickname,
        @Nullable URI profileImageUrl,
        @Nullable Instant profileImageUrlExpiresAt
) {
}
