package com.harudle.block.service.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record BlockPageResult(List<BlockedUserResult> items, @Nullable String nextCursor, boolean hasNext) {
    public BlockPageResult {
        items = List.copyOf(items);
    }
}
