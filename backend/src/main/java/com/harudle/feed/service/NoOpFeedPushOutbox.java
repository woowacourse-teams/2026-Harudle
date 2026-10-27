package com.harudle.feed.service;

import com.harudle.push.service.port.FeedPushOutbox;
import java.util.Objects;
import java.util.UUID;

/** 푸시 구현이 없을 때만 사용한다. 예약 기록을 남기지 않으며 소급 발송하지 않는다. */
enum NoOpFeedPushOutbox implements FeedPushOutbox {
    INSTANCE;

    @Override
    public int enqueuePublished(FeedPublished event) {
        Objects.requireNonNull(event);
        return 0;
    }

    @Override
    public int cancelUnsentByFeed(UUID feedId) {
        Objects.requireNonNull(feedId);
        return 0;
    }

    @Override
    public int cancelUnsentBetweenUsers(UUID firstUserId, UUID secondUserId) {
        Objects.requireNonNull(firstUserId);
        Objects.requireNonNull(secondUserId);
        return 0;
    }
}
