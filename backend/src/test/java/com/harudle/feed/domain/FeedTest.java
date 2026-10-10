package com.harudle.feed.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
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
}
