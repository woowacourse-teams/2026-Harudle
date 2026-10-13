package com.harudle.comment.service;

import com.harudle.comment.query.CommentCursor;
import com.harudle.comment.service.exception.InvalidCommentCursorException;
import com.harudle.common.validation.CanonicalUuidParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CommentCursorCodec {

    private static final String VERSION = "1";
    private static final String KIND = "COMMENTS";
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final Pattern URL_SAFE_BASE64 = Pattern.compile("[A-Za-z0-9_-]+");

    public String encode(CommentCursor cursor) {
        Objects.requireNonNull(cursor, "cursor는 필수입니다.");
        String payload = String.join("|", VERSION, KIND, cursor.feedId().toString(),
                cursor.createdAt().toString(), cursor.id().toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<CommentCursor> decode(UUID feedId, String cursor) {
        Objects.requireNonNull(feedId, "feedId는 필수입니다.");
        if (cursor == null) {
            return Optional.empty();
        }
        if (cursor.length() > MAX_CURSOR_LENGTH || !URL_SAFE_BASE64.matcher(cursor).matches()) {
            throw new InvalidCommentCursorException();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(cursor)) {
                throw new InvalidCommentCursorException();
            }
            String[] fields = new String(bytes, StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 5 || !VERSION.equals(fields[0]) || !KIND.equals(fields[1])) {
                throw new InvalidCommentCursorException();
            }
            UUID cursorFeedId = CanonicalUuidParser.parse(fields[2])
                    .orElseThrow(InvalidCommentCursorException::new);
            UUID commentId = CanonicalUuidParser.parse(fields[4])
                    .orElseThrow(InvalidCommentCursorException::new);
            Instant createdAt = Instant.parse(fields[3]);
            // Bound externally supplied values before binding them as PostgreSQL timestamps.
            int year = createdAt.atOffset(ZoneOffset.UTC).getYear();
            if (!feedId.equals(cursorFeedId) || year < 1 || year > 9999 || createdAt.getNano() % 1000 != 0) {
                throw new InvalidCommentCursorException();
            }
            return Optional.of(new CommentCursor(cursorFeedId, createdAt, commentId));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw new InvalidCommentCursorException();
        }
    }
}
