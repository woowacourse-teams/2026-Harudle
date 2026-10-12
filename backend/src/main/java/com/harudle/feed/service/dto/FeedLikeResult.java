package com.harudle.feed.service.dto;

public record FeedLikeResult(boolean likedByMe, int likeCount) {

    public FeedLikeResult {
        if (likeCount < 0) {
            throw new IllegalArgumentException("좋아요 수는 0 이상이어야 합니다.");
        }
    }
}
