package com.harudle.feed.presentation;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

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

    public record Author(UUID id, String nickname, URI profileImageUrl) {}

    public record Category(long id, String name) {}
}
