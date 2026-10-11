package com.harudle.feed.service.dto;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.profile.service.port.PublicProfileReader;
import java.time.Instant;
import java.util.UUID;

public record FeedResult(
        UUID id,
        PublicProfileReader.Profile author,
        CategoryReader.Category category,
        String imageObjectKey,
        Instant publishedAt,
        int likeCount,
        int commentCount,
        boolean likedByMe,
        boolean isMine
) {
}
