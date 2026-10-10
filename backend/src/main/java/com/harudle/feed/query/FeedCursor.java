package com.harudle.feed.query;

import com.harudle.feed.repository.FeedSnapshot;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record FeedCursor(
        FeedSort sort,
        @Nullable Long categoryId,
        Instant publishedAt,
        UUID feedId,
        int likeCount
) {
    private static final Instant MIN_PUBLISHED_AT = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_PUBLISHED_AT = Instant.parse("9999-12-31T23:59:59.999999Z");

    public FeedCursor {
        Objects.requireNonNull(sort);
        Objects.requireNonNull(publishedAt);
        Objects.requireNonNull(feedId);
        if ((categoryId != null && categoryId <= 0) || likeCount < 0
                || (sort == FeedSort.LATEST && likeCount != 0)
                || publishedAt.getNano() % 1_000 != 0
                || publishedAt.isBefore(MIN_PUBLISHED_AT)
                || publishedAt.isAfter(MAX_PUBLISHED_AT)) {
            throw new IllegalArgumentException("피드 커서 값이 올바르지 않습니다.");
        }
    }

    public static FeedCursor after(FeedSort sort, @Nullable Long categoryId, FeedSnapshot feed) {
        return new FeedCursor(sort, categoryId, feed.publishedAt(), feed.id(),
                sort == FeedSort.POPULAR ? feed.likeCount() : 0);
    }
}
