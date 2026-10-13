package com.harudle.feed.service;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.feed.service.exception.FeedIntegrationUnavailableException;
import com.harudle.feed.service.port.FeedLikeReader;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.push.service.port.FeedPushOutbox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 조회 포트는 필수이며, 푸시는 실제 구현이 연결될 때까지 생략한다. */
@Component
public class FeedCollaborators {

    private final ObjectProvider<CategoryReader> categories;
    private final ObjectProvider<PublicProfileReader> profiles;
    private final ObjectProvider<FeedLikeReader> likes;
    private final ObjectProvider<FeedPushOutbox> pushOutbox;

    public FeedCollaborators(
            ObjectProvider<CategoryReader> categories,
            ObjectProvider<PublicProfileReader> profiles,
            ObjectProvider<FeedLikeReader> likes,
            ObjectProvider<FeedPushOutbox> pushOutbox
    ) {
        this.categories = categories;
        this.profiles = profiles;
        this.likes = likes;
        this.pushOutbox = pushOutbox;
    }

    CategoryReader categories() {
        return require(categories, CategoryReader.class);
    }

    PublicProfileReader profiles() {
        return require(profiles, PublicProfileReader.class);
    }

    FeedLikeReader likes() {
        return require(likes, FeedLikeReader.class);
    }

    FeedPushOutbox pushOutbox() {
        FeedPushOutbox collaborator = pushOutbox.getIfAvailable();
        return collaborator == null ? NoOpFeedPushOutbox.INSTANCE : collaborator;
    }

    private static <T> T require(ObjectProvider<T> provider, Class<T> type) {
        T collaborator = provider.getIfAvailable();
        if (collaborator == null) {
            throw new FeedIntegrationUnavailableException(type);
        }
        return collaborator;
    }
}
