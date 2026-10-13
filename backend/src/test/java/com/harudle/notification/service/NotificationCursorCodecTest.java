package com.harudle.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.notification.query.NotificationCursor;
import com.harudle.notification.service.exception.InvalidNotificationCursorException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationCursorCodecTest {
    private static final UUID RECIPIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NOTIFICATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant CREATED_AT = Instant.parse("2026-10-12T03:00:00.123456Z");
    private final NotificationCursorCodec codec = new NotificationCursorCodec();

    @Test
    void absentCursorStartsFirstPage() {
        assertThat(codec.decode(RECIPIENT_ID, null)).isEmpty();
    }

    @Test
    void preservesOwnerTimestampPrecisionAndNotificationId() {
        NotificationCursor cursor = new NotificationCursor(RECIPIENT_ID, CREATED_AT, NOTIFICATION_ID);
        String encoded = codec.encode(cursor);
        assertThat(encoded).matches("[A-Za-z0-9_-]+").doesNotContain("=");
        assertThat(codec.decode(RECIPIENT_ID, encoded)).contains(cursor);
    }

    @Test
    void bindsCursorToAuthenticatedRecipient() {
        String encoded = codec.encode(new NotificationCursor(RECIPIENT_ID, CREATED_AT, NOTIFICATION_ID));
        assertThatThrownBy(() -> codec.decode(UUID.randomUUID(), encoded))
                .isInstanceOf(InvalidNotificationCursorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "%", "abc=", "AA", "a", "a-b_c\n"})
    void rejectsMalformedOrNonCanonicalEncoding(String encoded) {
        assertThatThrownBy(() -> codec.decode(RECIPIENT_ID, encoded))
                .isInstanceOf(InvalidNotificationCursorException.class);
    }

    @Test
    void boundsSizeBeforeBase64Decoding() {
        assertThatThrownBy(() -> codec.decode(RECIPIENT_ID, "a".repeat(513)))
                .isInstanceOf(InvalidNotificationCursorException.class);
    }

    @Test
    void rejectsOtherCursorKindsVersionsOrFieldCounts() {
        for (String payload : new String[]{
                "2|NOTIFICATIONS|" + RECIPIENT_ID + "|" + CREATED_AT + "|" + NOTIFICATION_ID,
                "1|COMMENTS|" + RECIPIENT_ID + "|" + CREATED_AT + "|" + NOTIFICATION_ID,
                "1|FEEDS|" + RECIPIENT_ID + "|" + CREATED_AT + "|" + NOTIFICATION_ID,
                "1|NOTIFICATIONS|" + RECIPIENT_ID + "|" + CREATED_AT,
                "1|NOTIFICATIONS|" + RECIPIENT_ID + "|" + CREATED_AT + "|" + NOTIFICATION_ID + "|extra",
                "1|NOTIFICATIONS|" + RECIPIENT_ID + "|" + CREATED_AT + "|"
        }) {
            assertInvalid(payload);
        }
    }

    @Test
    void rejectsNonCanonicalUuidAndMalformedTimestampBoundaries() {
        for (String payload : new String[]{
                "1|NOTIFICATIONS|1-1-1-1-1|" + CREATED_AT + "|" + NOTIFICATION_ID,
                "1|NOTIFICATIONS|" + RECIPIENT_ID + "|" + CREATED_AT + "|1-1-1-1-1",
                "1|NOTIFICATIONS|" + RECIPIENT_ID + "|bad-time|" + NOTIFICATION_ID,
                "1|NOTIFICATIONS|" + RECIPIENT_ID + "|2026-10-12T03:00:00.123456789Z|" + NOTIFICATION_ID,
                "1|NOTIFICATIONS|" + RECIPIENT_ID + "|0000-01-01T00:00:00Z|" + NOTIFICATION_ID,
                "1|NOTIFICATIONS|" + RECIPIENT_ID + "|+10000-01-01T00:00:00Z|" + NOTIFICATION_ID
        }) {
            assertInvalid(payload);
        }
    }

    @Test
    void rejectsInvalidUtf8Payload() {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{(byte) 0xff, (byte) 0xfe});
        assertThatThrownBy(() -> codec.decode(RECIPIENT_ID, encoded))
                .isInstanceOf(InvalidNotificationCursorException.class);
    }

    @Test
    void guardsInternalNonnullBoundary() {
        assertThatThrownBy(() -> codec.decode(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> codec.encode(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NotificationCursor(null, CREATED_AT, NOTIFICATION_ID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NotificationCursor(RECIPIENT_ID, null, NOTIFICATION_ID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NotificationCursor(RECIPIENT_ID, CREATED_AT, null))
                .isInstanceOf(NullPointerException.class);
    }

    private void assertInvalid(String payload) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.decode(RECIPIENT_ID, encoded))
                .as("payload: %s", payload).isInstanceOf(InvalidNotificationCursorException.class);
    }
}
