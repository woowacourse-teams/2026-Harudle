package com.harudle.feed.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record FeedResponse(
        UUID id,
        Author author,
        Category category,
        URI imageUrl,
        OffsetDateTime imageUrlExpiresAt,
        OffsetDateTime publishedAt,
        int likeCount,
        int commentCount,
        boolean likedByMe,
        boolean isMine,
        URI shareUrl
) {

    public record Author(
            UUID id,
            String nickname,
            URI profileImageUrl,
            @Schema(description = "개인 프로필 이미지 URL 만료 시각. 기본 이미지는 null",
                    type = "string", format = "date-time", nullable = true)
            @Nullable Instant profileImageUrlExpiresAt
    ) {}

    public record Category(long id, String name) {}
}
