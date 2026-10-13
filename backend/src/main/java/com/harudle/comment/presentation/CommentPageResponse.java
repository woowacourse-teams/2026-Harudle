package com.harudle.comment.presentation;

import com.harudle.comment.service.dto.CommentPageResult;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record CommentPageResponse(List<CommentResponse> items, @Nullable String nextCursor, boolean hasNext) {
    static CommentPageResponse from(CommentPageResult page) {
        return new CommentPageResponse(page.items().stream().map(CommentResponse::from).toList(),
                page.nextCursor(), page.hasNext());
    }
}
