package com.harudle.feed.service.port;

import java.util.Set;
import java.util.UUID;

/** 화면에서 내 좋아요 여부(likedByMe)를 표시할 때 사용한다. */
public interface FeedLikeReader {

    /**
     * 전달받은 피드 중 내가 좋아요 한 피드 ID만 반환한다. 삭제된 피드는 제외한다.
     * 입력값은 null을 허용하지 않는다. 빈 Set을 받거나 결과가 없으면 빈 Set을 반환한다.
     * 비로그인 사용자는 호출하지 않고 likedByMe를 false로 표시한다.
     */
    Set<UUID> findLikedFeedIds(UUID viewerId, Set<UUID> feedIds);
}
