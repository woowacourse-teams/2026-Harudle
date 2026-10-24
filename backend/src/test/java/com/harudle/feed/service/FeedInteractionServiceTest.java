package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.feed.domain.Feed;
import com.harudle.feed.repository.FeedInteractionRepository;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedInteractionPort.CounterChange;
import com.harudle.feed.service.port.FeedInteractionPort.CounterKind;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FeedInteractionServiceTest {
    private static final UUID AUTHOR = UUID.randomUUID();
    @Mock private FeedInteractionRepository feeds;
    private FeedInteractionService service;

    @BeforeEach
    void setUp() {
        service = new FeedInteractionService(feeds);
    }

    @Test
    void returnsAuthorAndCountsFromLockedFeed() {
        Feed feed = activeFeed();
        feed.increaseLikeCount();
        var target = service.lockActive(feed.getId());
        assertThat(target.authorId()).isEqualTo(AUTHOR);
        assertThat(target.counts().likeCount()).isEqualTo(1);
        verify(feeds).findByIdForUpdate(feed.getId());
    }

    @Test
    void adjustsOnlyRequestedCounterUsingDomainMethods() {
        Feed feed = activeFeed();
        assertThat(service.adjustCounter(feed.getId(), CounterKind.LIKE, CounterChange.ADDED).likeCount()).isEqualTo(1);
        var counts = service.adjustCounter(feed.getId(), CounterKind.COMMENT, CounterChange.ADDED);
        assertThat(counts.likeCount()).isEqualTo(1);
        assertThat(counts.commentCount()).isEqualTo(1);
        assertThat(service.adjustCounter(feed.getId(), CounterKind.LIKE, CounterChange.REMOVED).likeCount()).isZero();
        assertThat(service.adjustCounter(feed.getId(), CounterKind.COMMENT, CounterChange.REMOVED).commentCount()).isZero();
    }

    @Test
    void rejectsMissingFeed() {
        UUID missing = UUID.randomUUID();
        when(feeds.findByIdForUpdate(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.lockActive(missing)).isInstanceOf(FeedNotFoundException.class);
    }

    @Test
    void rejectsDeletedFeedBeforeAuthorLookup() {
        Feed feed = Feed.publish(UUID.randomUUID(), 1, Instant.now());
        ReflectionTestUtils.setField(feed, "deletedAt", Instant.now());
        when(feeds.findByIdForUpdate(feed.getId())).thenReturn(Optional.of(feed));
        assertThatThrownBy(() -> service.adjustCounter(feed.getId(), CounterKind.LIKE, CounterChange.ADDED))
                .isInstanceOf(FeedNotFoundException.class);
        assertThat(feed.getLikeCount()).isZero();
    }

    @Test
    void rejectsDeletedDiaryOrUnavailableAuthorWithoutChangingCounts() {
        Feed feed = Feed.publish(UUID.randomUUID(), 1, Instant.now());
        when(feeds.findByIdForUpdate(feed.getId())).thenReturn(Optional.of(feed));
        when(feeds.findActiveAuthorId(feed.getDiaryId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.lockActive(feed.getId())).isInstanceOf(FeedNotFoundException.class);
        assertThatThrownBy(() -> service.adjustCounter(feed.getId(), CounterKind.LIKE, CounterChange.ADDED))
                .isInstanceOf(FeedNotFoundException.class);
        assertThat(feed.getLikeCount()).isZero();
    }

    private Feed activeFeed() {
        Feed feed = Feed.publish(UUID.randomUUID(), 1, Instant.now());
        when(feeds.findByIdForUpdate(feed.getId())).thenReturn(Optional.of(feed));
        when(feeds.findActiveAuthorId(feed.getDiaryId())).thenReturn(Optional.of(AUTHOR));
        return feed;
    }
}
