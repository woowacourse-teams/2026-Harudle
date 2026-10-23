package com.harudle.comment.service.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record CommentPageResult(List<CommentResult> items, @Nullable String nextCursor, boolean hasNext) {
    public CommentPageResult {
        items = List.copyOf(items);
        if (hasNext != (nextCursor != null)) {
            throw new IllegalArgumentException("다음 페이지 여부와 커서가 일치해야 합니다.");
        }
    }
}
