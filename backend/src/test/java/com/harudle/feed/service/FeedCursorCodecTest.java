package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.feed.query.FeedCursor;
import com.harudle.feed.query.FeedSort;
import com.harudle.feed.service.exception.InvalidFeedCursorException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class FeedCursorCodecTest {
    private final FeedCursorCodec codec = new FeedCursorCodec();

    @ParameterizedTest
    @EnumSource(FeedSort.class)
    void roundTripsSortBoundaryAndCategoryWithMicrosecondPrecision(FeedSort sort) {
        FeedCursor cursor = cursor(sort, 12L);
        String encoded = codec.encode(cursor);
        assertThat(encoded).doesNotContain("+", "/", "=");
        assertThat(codec.decode(encoded, sort, 12L)).isEqualTo(cursor);
    }

    @Test
    void acceptsMissingCursorForFirstPage() {
        assertThat(codec.decode(null, FeedSort.LATEST, null)).isNull();
    }

    @Test
    void rejectsCursorReusedForDifferentSortOrCategory() {
        String encoded = codec.encode(cursor(FeedSort.POPULAR, 1L));
        assertThatThrownBy(() -> codec.decode(encoded, FeedSort.LATEST, 1L))
                .isInstanceOf(InvalidFeedCursorException.class);
        assertThatThrownBy(() -> codec.decode(encoded, FeedSort.POPULAR, 2L))
                .isInstanceOf(InvalidFeedCursorException.class);
        assertThatThrownBy(() -> codec.decode(encoded, FeedSort.POPULAR, null))
                .isInstanceOf(InvalidFeedCursorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-a-cursor", "!@#$"})
    void rejectsMalformedCursor(String encoded) {
        assertThatThrownBy(() -> codec.decode(encoded, FeedSort.LATEST, null))
                .isInstanceOf(InvalidFeedCursorException.class);
    }

    @Test
    void rejectsUnknownVersionTrailingDataAndOversizedCursor() {
        byte[] bytes = Base64.getUrlDecoder().decode(codec.encode(cursor(FeedSort.LATEST, null)));
        byte[] unknownVersion = bytes.clone();
        unknownVersion[3] = 2;
        for (String invalid : Arrays.asList(Base64.getUrlEncoder().encodeToString(unknownVersion),
                Base64.getUrlEncoder().encodeToString(Arrays.copyOf(bytes, bytes.length + 1)), "a".repeat(129))) {
            assertThatThrownBy(() -> codec.decode(invalid, FeedSort.LATEST, null))
                    .isInstanceOf(InvalidFeedCursorException.class);
        }
    }

    private static FeedCursor cursor(FeedSort sort, Long categoryId) {
        return new FeedCursor(sort, categoryId, Instant.parse("2026-10-11T01:00:00.123456Z"),
                UUID.fromString("ff000000-0000-0000-0000-000000000001"), sort == FeedSort.POPULAR ? 12 : 0);
    }
}
