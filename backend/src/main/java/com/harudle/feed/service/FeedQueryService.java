package com.harudle.feed.service;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.feed.repository.FeedQueryRepository;
import com.harudle.feed.repository.FeedSnapshot;
import com.harudle.feed.service.dto.FeedResult;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.profile.service.port.PublicProfileReader;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FeedQueryService {

    private final FeedQueryRepository feeds;
    private final FeedCollaborators collaborators;

    public FeedQueryService(FeedQueryRepository feeds, FeedCollaborators collaborators) {
        this.feeds = feeds;
        this.collaborators = collaborators;
    }

    public FeedResult getDetail(@Nullable UUID viewerId, UUID feedId) {
        Objects.requireNonNull(feedId, "피드 ID는 필수입니다.");
        FeedSnapshot feed = feeds.findActiveSnapshotById(feedId).orElseThrow(FeedNotFoundException::new);
        PublicProfileReader.Profile author = collaborators.profiles()
                .readAll(Set.of(feed.authorId())).get(feed.authorId());
        if (author == null) {
            throw new FeedNotFoundException();
        }
        CategoryReader.Category category = collaborators.categories().get(feed.categoryId());
        boolean likedByMe = viewerId != null
                && collaborators.likes().findLikedFeedIds(viewerId, Set.of(feed.id())).contains(feed.id());
        return new FeedResult(
                feed.id(), author, category, feed.imageObjectKey(), feed.publishedAt(),
                feed.likeCount(), feed.commentCount(), likedByMe, feed.authorId().equals(viewerId)
        );
    }
}
