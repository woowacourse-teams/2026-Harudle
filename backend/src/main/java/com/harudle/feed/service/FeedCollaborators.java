package com.harudle.feed.service;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.feed.service.exception.FeedIntegrationUnavailableException;
import com.harudle.feed.service.port.FeedLikeReader;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.push.service.port.FeedPushOutbox;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 다른 담당 영역의 구현체가 합쳐질 때 기존 포트로 연결한다. 미연동을 성공으로 처리하지 않는다. */
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
        return require(pushOutbox, FeedPushOutbox.class);
    }

    private static <T> T require(ObjectProvider<T> provider, Class<T> type) {
        T collaborator = provider.getIfAvailable();
        if (collaborator == null) {
            throw new FeedIntegrationUnavailableException(type);
        }
        return collaborator;
    }
}
