package com.harudle.feed.service;

import com.harudle.block.service.BlockInteractionPolicy;
import com.harudle.feed.domain.Feed;
import com.harudle.feed.repository.FeedInteractionRepository;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedInteractionPort;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class FeedInteractionService implements FeedInteractionPort {
    private final FeedInteractionRepository feeds;
    private final BlockInteractionPolicy blocks;

    public FeedInteractionService(FeedInteractionRepository feeds, BlockInteractionPolicy blocks) {
        this.feeds = feeds;
        this.blocks = blocks;
    }

    @Override
    public Target lockActive(UUID feedId) {
        Feed feed = lockFeed(feedId);
        UUID authorId = activeAuthor(feed);
        return new Target(feedId, authorId, counts(feed));
    }

    @Override
    public Target lockActiveForInteraction(UUID feedId, UUID actorId) {
        Objects.requireNonNull(actorId);
        Feed feed = lockFeed(feedId);
        UUID authorId = feeds.findActiveAuthorIdForInteraction(feed.getDiaryId(), actorId)
                .orElseThrow(FeedNotFoundException::new);
        // 피드 행 → 사용자 쌍 순서로 잠근다. 차단과 반응 저장 중 먼저 완료된 작업을 기준으로 판단한다.
        if (blocks.lockAndIsBlocked(actorId, authorId)) {
            throw new FeedNotFoundException();
        }
        return new Target(feedId, authorId, counts(feed));
    }

    @Override
    public Counts adjustCounter(UUID feedId, CounterKind kind, CounterChange change) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(change);
        Feed feed = lockFeed(feedId);
        activeAuthor(feed);
        switch (kind) {
            case LIKE -> {
                if (change == CounterChange.ADDED) {
                    feed.increaseLikeCount();
                } else {
                    feed.decreaseLikeCount();
                }
            }
            case COMMENT -> {
                if (change == CounterChange.ADDED) {
                    feed.increaseCommentCount();
                } else {
                    feed.decreaseCommentCount();
                }
            }
        }
        return counts(feed);
    }

    private Feed lockFeed(UUID feedId) {
        Objects.requireNonNull(feedId);
        Feed feed = feeds.findByIdForUpdate(feedId).orElseThrow(FeedNotFoundException::new);
        if (feed.isDeleted()) {
            throw new FeedNotFoundException();
        }
        return feed;
    }

    private UUID activeAuthor(Feed feed) {
        return feeds.findActiveAuthorId(feed.getDiaryId()).orElseThrow(FeedNotFoundException::new);
    }

    private static Counts counts(Feed feed) {
        return new Counts(feed.getLikeCount(), feed.getCommentCount());
    }
}
