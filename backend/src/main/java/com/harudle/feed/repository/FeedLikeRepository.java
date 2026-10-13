package com.harudle.feed.repository;

import java.time.Instant;
import java.util.UUID;

/** 좋아요의 실제 추가/삭제 여부를 반환한다. 변경은 잠근 피드의 트랜잭션에 참여한다. */
public interface FeedLikeRepository {

    boolean isActiveMember(UUID userId);

    boolean add(UUID likeId, UUID feedId, UUID userId, Instant createdAt);

    boolean remove(UUID feedId, UUID userId);
}
