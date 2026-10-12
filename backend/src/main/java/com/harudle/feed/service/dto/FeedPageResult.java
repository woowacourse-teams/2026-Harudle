package com.harudle.feed.service.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record FeedPageResult(List<FeedResult> items, @Nullable String nextCursor, boolean hasNext) {
    public FeedPageResult {
        items = List.copyOf(items);
    }
}
