package com.harudle.feed.service;

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

    public FeedInteractionService(FeedInteractionRepository feeds) {
        this.feeds = feeds;
    }

    @Override
    public Target lockActive(UUID feedId) {
        Feed feed = lockFeed(feedId);
        UUID authorId = activeAuthor(feed);
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
