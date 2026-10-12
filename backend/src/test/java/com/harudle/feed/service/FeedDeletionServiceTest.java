package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.feed.domain.Feed;
import com.harudle.feed.repository.FeedDeletionRepository;
import com.harudle.feed.repository.FeedInteractionRepository;
import com.harudle.feed.service.exception.FeedAccessDeniedException;
import com.harudle.feed.service.exception.FeedIntegrationUnavailableException;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.push.service.port.FeedPushOutbox;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeedDeletionServiceTest {
    private static final UUID AUTHOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-11T02:00:00Z");
    @Mock private FeedInteractionRepository feeds;
    @Mock private FeedDeletionRepository deletions;
    @Mock private FeedCollaborators collaborators;
    @Mock private FeedPushOutbox outbox;
    private FeedDeletionService service;

    @BeforeEach
    void setUp() {
        service = new FeedDeletionService(feeds, deletions, collaborators, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void deletesOwnedFeedAndCancelsUnsentPushEvenWhenThereAreNoJobs() {
        Feed feed = ownedFeed();
        feed.increaseLikeCount();
        when(collaborators.pushOutbox()).thenReturn(outbox);
        service.delete(AUTHOR, feed.getId());
        assertThat(feed.getDeletedAt()).isEqualTo(NOW);
        assertThat(feed.getLikeCount()).isEqualTo(1);
        verify(outbox).cancelUnsentByFeed(feed.getId());
        verifyNoInteractions(deletions);
    }

    @Test
    void rejectsOtherAuthorBeforeDeletionOrPushCancellation() {
        Feed feed = ownedFeed();
        assertThatThrownBy(() -> service.delete(UUID.randomUUID(), feed.getId()))
                .isInstanceOf(FeedAccessDeniedException.class);
        assertThat(feed.isDeleted()).isFalse();
        verifyNoInteractions(collaborators, outbox);
    }

    @Test
    void rejectsMissingFeed() {
        UUID missing = UUID.randomUUID();
        when(feeds.findByIdForUpdate(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(AUTHOR, missing)).isInstanceOf(FeedNotFoundException.class);
        verifyNoInteractions(collaborators, outbox);
    }

    @Test
    void rejectsDeletedFeedWithoutChangingItsTimestamp() {
        Feed feed = Feed.publish(UUID.randomUUID(), 1, NOW.minusSeconds(60));
        Instant deletedAt = NOW.minusSeconds(10);
        feed.delete(deletedAt);
        when(feeds.findByIdForUpdate(feed.getId())).thenReturn(Optional.of(feed));
        assertThatThrownBy(() -> service.delete(AUTHOR, feed.getId())).isInstanceOf(FeedNotFoundException.class);
        assertThat(feed.getDeletedAt()).isEqualTo(deletedAt);
        verifyNoInteractions(collaborators, outbox);
    }

    @Test
    void rejectsDeletedDiaryOrUnavailableAuthor() {
        Feed feed = Feed.publish(UUID.randomUUID(), 1, NOW.minusSeconds(60));
        when(feeds.findByIdForUpdate(feed.getId())).thenReturn(Optional.of(feed));
        when(feeds.findActiveAuthorId(feed.getDiaryId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(AUTHOR, feed.getId())).isInstanceOf(FeedNotFoundException.class);
        assertThat(feed.isDeleted()).isFalse();
        verifyNoInteractions(collaborators, outbox);
    }

    @Test
    void failsBeforeStateChangeWhenPushAdapterIsUnavailable() {
        Feed feed = ownedFeed();
        when(collaborators.pushOutbox()).thenThrow(new FeedIntegrationUnavailableException(FeedPushOutbox.class));
        assertThatThrownBy(() -> service.delete(AUTHOR, feed.getId()))
                .isInstanceOf(FeedIntegrationUnavailableException.class);
        assertThat(feed.isDeleted()).isFalse();
    }

    @Test
    void propagatesPushCancellationFailureToCallersTransaction() {
        Feed feed = ownedFeed();
        when(collaborators.pushOutbox()).thenReturn(outbox);
        when(outbox.cancelUnsentByFeed(feed.getId())).thenThrow(new IllegalStateException("푸시 취소 저장 실패"));
        assertThatThrownBy(() -> service.delete(AUTHOR, feed.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("푸시 취소 저장 실패");
    }

    @Test
    void diaryLifecycleUsesProvidedTimestampAndOnlyLocksConnectedFeed() {
        Feed feed = Feed.publish(UUID.randomUUID(), 1, NOW.minusSeconds(60));
        when(deletions.findActiveByDiaryIdForUpdate(feed.getDiaryId())).thenReturn(Optional.of(feed));
        when(collaborators.pushOutbox()).thenReturn(outbox);
        Instant diaryDeletionTime = NOW.minusSeconds(30);
        service.deleteByDiary(feed.getDiaryId(), diaryDeletionTime);
        assertThat(feed.getDeletedAt()).isEqualTo(diaryDeletionTime);
        verify(outbox).cancelUnsentByFeed(feed.getId());
        verifyNoInteractions(feeds);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void diaryLifecycleDoesNothingForMissingOrDeletedConnection(boolean alreadyDeleted) {
        Feed feed = Feed.publish(UUID.randomUUID(), 1, NOW.minusSeconds(60));
        feed.delete(NOW.minusSeconds(30));
        when(deletions.findActiveByDiaryIdForUpdate(feed.getDiaryId()))
                .thenReturn(alreadyDeleted ? Optional.of(feed) : Optional.empty());
        service.deleteByDiary(feed.getDiaryId(), NOW);
        assertThat(feed.getDeletedAt()).isEqualTo(NOW.minusSeconds(30));
        verifyNoInteractions(feeds, collaborators, outbox);
    }

    private Feed ownedFeed() {
        Feed feed = Feed.publish(UUID.randomUUID(), 1, NOW.minusSeconds(60));
        when(feeds.findByIdForUpdate(feed.getId())).thenReturn(Optional.of(feed));
        when(feeds.findActiveAuthorId(feed.getDiaryId())).thenReturn(Optional.of(AUTHOR));
        return feed;
    }
}
