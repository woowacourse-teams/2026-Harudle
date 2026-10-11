package com.harudle.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeedTest {

    @Test
    void rejectsNonPositiveCategory() {
        assertThatThrownBy(() -> Feed.publish(UUID.randomUUID(), 0, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesPostgresTimestampPrecisionSoPublicationAndReadAgree() {
        Instant now = Instant.parse("2026-10-11T01:00:00.123456789Z");
        Feed feed = Feed.publish(UUID.randomUUID(), 1, now);
        assertThat(feed.getPublishedAt()).isEqualTo(Instant.parse("2026-10-11T01:00:00.123456Z"));
    }

    @Test
    void independentlyAdjustsLikeAndCommentCounts() {
        Feed feed = Feed.publish(UUID.randomUUID(), 1, Instant.now());
        feed.increaseLikeCount();
        feed.increaseLikeCount();
        feed.increaseCommentCount();
        feed.decreaseLikeCount();
        assertThat(feed.getLikeCount()).isEqualTo(1);
        assertThat(feed.getCommentCount()).isEqualTo(1);
        feed.decreaseCommentCount();
        assertThat(feed.getCommentCount()).isZero();
    }

    @Test
    void refusesToDecreaseCountersBelowZero() {
        Feed feed = Feed.publish(UUID.randomUUID(), 1, Instant.now());
        assertThatThrownBy(feed::decreaseLikeCount).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(feed::decreaseCommentCount).isInstanceOf(IllegalStateException.class);
        assertThat(feed.getLikeCount()).isZero();
        assertThat(feed.getCommentCount()).isZero();
    }

    @Test
    void softDeletionKeepsOriginalPublicationAndCountsAndDoesNotRewriteTimestamp() {
        UUID diaryId = UUID.randomUUID();
        Instant publishedAt = Instant.parse("2026-10-11T01:00:00Z");
        Feed feed = Feed.publish(diaryId, 1, publishedAt);
        feed.increaseLikeCount();
        feed.increaseCommentCount();
        Instant firstDeletion = Instant.parse("2026-10-11T02:00:00.123456789Z");
        feed.delete(firstDeletion);
        feed.delete(firstDeletion.plusSeconds(60));
        assertThat(feed.isDeleted()).isTrue();
        assertThat(feed.getDeletedAt()).isEqualTo(firstDeletion.truncatedTo(ChronoUnit.MICROS));
        assertThat(feed.getDiaryId()).isEqualTo(diaryId);
        assertThat(feed.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(feed.getLikeCount()).isEqualTo(1);
        assertThat(feed.getCommentCount()).isEqualTo(1);
    }

    @Test
    void rejectsAllCounterChangesAfterDeletion() {
        Feed feed = Feed.publish(UUID.randomUUID(), 1, Instant.now());
        feed.delete(Instant.now());
        assertThatThrownBy(feed::increaseLikeCount).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(feed::decreaseLikeCount).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(feed::increaseCommentCount).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(feed::decreaseCommentCount).isInstanceOf(IllegalStateException.class);
        assertThat(feed.getLikeCount()).isZero();
        assertThat(feed.getCommentCount()).isZero();
    }
}
