package com.harudle.notification.service;

import com.harudle.common.validation.CanonicalUuidParser;
import com.harudle.notification.query.NotificationCursor;
import com.harudle.notification.service.exception.InvalidNotificationCursorException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class NotificationCursorCodec {
    private static final String VERSION = "1";
    private static final String KIND = "NOTIFICATIONS";
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final Pattern URL_SAFE_BASE64 = Pattern.compile("[A-Za-z0-9_-]+");

    public String encode(NotificationCursor cursor) {
        Objects.requireNonNull(cursor, "cursor는 필수입니다.");
        String payload = String.join("|", VERSION, KIND, cursor.recipientId().toString(),
                cursor.createdAt().toString(), cursor.id().toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<NotificationCursor> decode(UUID recipientId, @Nullable String encoded) {
        Objects.requireNonNull(recipientId, "recipientId는 필수입니다.");
        if (encoded == null) {
            return Optional.empty();
        }
        if (encoded.length() > MAX_CURSOR_LENGTH || !URL_SAFE_BASE64.matcher(encoded).matches()) {
            throw new InvalidNotificationCursorException();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
                throw new InvalidNotificationCursorException();
            }
            String[] fields = new String(bytes, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 5 || !VERSION.equals(fields[0]) || !KIND.equals(fields[1])) {
                throw new InvalidNotificationCursorException();
            }
            UUID cursorRecipientId = CanonicalUuidParser.parse(fields[2])
                    .orElseThrow(InvalidNotificationCursorException::new);
            UUID notificationId = CanonicalUuidParser.parse(fields[4])
                    .orElseThrow(InvalidNotificationCursorException::new);
            Instant createdAt = Instant.parse(fields[3]);
            int year = createdAt.atOffset(ZoneOffset.UTC).getYear();
            if (!recipientId.equals(cursorRecipientId) || year < 1 || year > 9999 || createdAt.getNano() % 1000 != 0) {
                throw new InvalidNotificationCursorException();
            }
            return Optional.of(new NotificationCursor(cursorRecipientId, createdAt, notificationId));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw new InvalidNotificationCursorException();
        }
    }
}
