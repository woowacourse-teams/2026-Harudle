package com.harudle.comment.presentation;

import com.harudle.comment.service.dto.CommentResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
        UUID id, CommentAuthorResponse author, String content, Instant createdAt,
        @Schema(description = "현재 로그인한 사용자가 작성한 댓글인지 여부") boolean isMine
) {
    static CommentResponse from(CommentResult comment) {
        return new CommentResponse(comment.id(), CommentAuthorResponse.from(comment.author()),
                comment.content(), comment.createdAt(), comment.isMine());
    }
}
