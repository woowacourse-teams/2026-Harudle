package com.harudle.feed.presentation;

import com.harudle.feed.service.dto.FeedLikeResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record FeedLikeResponse(
        @Schema(description = "현재 로그인 사용자의 피드 좋아요 여부") boolean likedByMe,
        @Schema(description = "피드의 현재 좋아요 수", minimum = "0") int likeCount
) {

    static FeedLikeResponse from(FeedLikeResult result) {
        return new FeedLikeResponse(result.likedByMe(), result.likeCount());
    }
}
