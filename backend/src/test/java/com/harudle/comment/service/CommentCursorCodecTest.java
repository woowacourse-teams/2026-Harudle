package com.harudle.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.comment.query.CommentCursor;
import com.harudle.comment.service.exception.InvalidCommentCursorException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommentCursorCodecTest {

    private static final UUID FEED_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant CREATED_AT = Instant.parse("2026-10-12T03:00:00.123456Z");

    private final CommentCursorCodec codec = new CommentCursorCodec();

    @Test
    void absentCursorMeansFirstPage() {
        assertThat(codec.decode(FEED_ID, null)).isEmpty();
    }

    @Test
    void roundTripsFeedTimestampPrecisionAndCommentId() {
        CommentCursor cursor = new CommentCursor(FEED_ID, CREATED_AT, COMMENT_ID);
        String encoded = codec.encode(cursor);
        assertThat(encoded).matches("[A-Za-z0-9_-]+").doesNotContain("=");
        assertThat(codec.decode(FEED_ID, encoded)).contains(cursor);
    }

    @Test
    void rejectsCursorFromAnotherFeed() {
        String encoded = codec.encode(new CommentCursor(FEED_ID, CREATED_AT, COMMENT_ID));
        assertThatThrownBy(() -> codec.decode(UUID.randomUUID(), encoded))
                .isInstanceOf(InvalidCommentCursorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "%", "abc=", "AA", "a"})
    void rejectsMalformedOrNonCanonicalEncoding(String cursor) {
        assertThatThrownBy(() -> codec.decode(FEED_ID, cursor))
                .isInstanceOf(InvalidCommentCursorException.class);
    }

    @Test
    void boundsCursorSizeBeforeDecoding() {
        assertThatThrownBy(() -> codec.decode(FEED_ID, "a".repeat(513)))
                .isInstanceOf(InvalidCommentCursorException.class);
    }

    @Test
    void rejectsUnknownVersionKindFieldsAndMalformedBoundaryValues() {
        for (String payload : new String[]{
                "2|COMMENTS|" + FEED_ID + "|" + CREATED_AT + "|" + COMMENT_ID,
                "1|FEEDS|" + FEED_ID + "|" + CREATED_AT + "|" + COMMENT_ID,
                "1|COMMENTS|" + FEED_ID + "|" + CREATED_AT,
                "1|COMMENTS|" + FEED_ID + "|" + CREATED_AT + "|" + COMMENT_ID + "|extra",
                "1|COMMENTS|1-1-1-1-1|" + CREATED_AT + "|" + COMMENT_ID,
                "1|COMMENTS|" + FEED_ID + "|bad-time|" + COMMENT_ID,
                "1|COMMENTS|" + FEED_ID + "|2026-10-12T03:00:00.123456789Z|" + COMMENT_ID,
                "1|COMMENTS|" + FEED_ID + "|" + CREATED_AT + "|1-1-1-1-1",
                "1|COMMENTS|" + FEED_ID + "|+10000-01-01T00:00:00Z|" + COMMENT_ID,
                "1|COMMENTS|" + FEED_ID + "|0000-01-01T00:00:00Z|" + COMMENT_ID
        }) {
            assertThatThrownBy(() -> codec.decode(FEED_ID, encoded(payload)))
                    .isInstanceOf(InvalidCommentCursorException.class);
        }
    }

    @Test
    void rejectsNullIdentifiersAtTheInternalBoundary() {
        assertThatThrownBy(() -> codec.decode(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> codec.encode(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommentCursor(null, CREATED_AT, COMMENT_ID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommentCursor(FEED_ID, null, COMMENT_ID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CommentCursor(FEED_ID, CREATED_AT, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static String encoded(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }
}
