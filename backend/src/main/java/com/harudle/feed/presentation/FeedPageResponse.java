package com.harudle.feed.presentation;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record FeedPageResponse(List<FeedResponse> items, @Nullable String nextCursor, boolean hasNext) {
}
