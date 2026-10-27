package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.harudle.block.service.BlockInteractionPolicy;
import com.harudle.common.persistence.PostgresTransactionLockTimeout;
import com.harudle.feed.domain.Feed;
import com.harudle.feed.repository.FeedInteractionRepository;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedInteractionPort.CounterChange;
import com.harudle.feed.service.port.FeedInteractionPort.CounterKind;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FeedInteractionServiceTest {
    private static final UUID AUTHOR = UUID.randomUUID();
    @Mock private FeedInteractionRepository feeds;
    @Mock private BlockInteractionPolicy blocks;
    @Mock private PostgresTransactionLockTimeout lockTimeout;
    private FeedInteractionService service;

    @BeforeEach
    void setUp() {
        service = new FeedInteractionService(feeds, blocks, lockTimeout);
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

    @Test
    void checksBlockAfterLockingFeedBeforeAllowingInteraction() {
        UUID actor = UUID.randomUUID();
        Feed feed = Feed.publish(UUID.randomUUID(), 1, Instant.now());
        when(feeds.findByIdForUpdate(feed.getId())).thenReturn(Optional.of(feed));
        when(feeds.findActiveAuthorIdForInteraction(feed.getDiaryId(), actor)).thenReturn(Optional.of(AUTHOR));

        var target = service.lockActiveForInteraction(feed.getId(), actor);

        assertThat(target.authorId()).isEqualTo(AUTHOR);
        var order = inOrder(lockTimeout, feeds, blocks);
        order.verify(lockTimeout).apply();
        order.verify(feeds).findByIdForUpdate(feed.getId());
        order.verify(feeds).findActiveAuthorIdForInteraction(feed.getDiaryId(), actor);
        order.verify(blocks).lockAndIsBlocked(actor, AUTHOR);
    }

    @Test
    void hidesBlockedFeedAndLeavesInteractionCountsUnchanged() {
        UUID actor = UUID.randomUUID();
        Feed feed = Feed.publish(UUID.randomUUID(), 1, Instant.now());
        when(feeds.findByIdForUpdate(feed.getId())).thenReturn(Optional.of(feed));
        when(feeds.findActiveAuthorIdForInteraction(feed.getDiaryId(), actor)).thenReturn(Optional.of(AUTHOR));
        when(blocks.lockAndIsBlocked(actor, AUTHOR)).thenReturn(true);

        assertThatThrownBy(() -> service.lockActiveForInteraction(feed.getId(), actor))
                .isInstanceOf(FeedNotFoundException.class);
        assertThat(feed.getLikeCount()).isZero();
        assertThat(feed.getCommentCount()).isZero();
    }

    @Test
    void doesNotExposeBlockInformationForUnavailableFeedAuthor() {
        UUID actor = UUID.randomUUID();
        Feed feed = Feed.publish(UUID.randomUUID(), 1, Instant.now());
        when(feeds.findByIdForUpdate(feed.getId())).thenReturn(Optional.of(feed));
        when(feeds.findActiveAuthorIdForInteraction(feed.getDiaryId(), actor)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lockActiveForInteraction(feed.getId(), actor))
                .isInstanceOf(FeedNotFoundException.class);
        verify(blocks, never()).lockAndIsBlocked(any(), any());
    }

    @Test
    @DisplayName("숨긴 피드는 접촉 전에 거절하지만 본인 댓글 정리용 잠금과 계수 변경은 유지한다")
    void rejectHiddenContactWithoutBlockingCleanup() {
        Feed feed = activeFeed();
        when(feeds.isHiddenForPublicUse(feed.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.lockActiveForInteraction(feed.getId(), AUTHOR))
                .isInstanceOf(FeedNotFoundException.class);
        verify(feeds, never()).findActiveAuthorIdForInteraction(feed.getDiaryId(), AUTHOR);
        assertThat(service.lockActive(feed.getId()).authorId()).isEqualTo(AUTHOR);
        assertThat(service.adjustCounter(feed.getId(), CounterKind.COMMENT, CounterChange.ADDED).commentCount())
                .isEqualTo(1);
    }

    private Feed activeFeed() {
        Feed feed = Feed.publish(UUID.randomUUID(), 1, Instant.now());
        when(feeds.findByIdForUpdate(feed.getId())).thenReturn(Optional.of(feed));
        when(feeds.findActiveAuthorId(feed.getDiaryId())).thenReturn(Optional.of(AUTHOR));
        return feed;
    }
}
