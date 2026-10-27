package com.harudle.feed.repository;

import java.time.Instant;
import java.util.UUID;

public record FeedSnapshot(
        UUID id,
        UUID authorId,
        long categoryId,
        String imageObjectKey,
        Instant publishedAt,
        int likeCount,
        int commentCount
) {
}
