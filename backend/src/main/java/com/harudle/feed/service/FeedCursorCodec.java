package com.harudle.feed.service;

import com.harudle.feed.query.FeedCursor;
import com.harudle.feed.query.FeedSort;
import com.harudle.feed.service.exception.InvalidFeedCursorException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class FeedCursorCodec {
    private static final int VERSION = 1;
    private static final int MAX_ENCODED_LENGTH = 128;

    public String encode(FeedCursor cursor) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                output.writeUTF(cursor.sort().name());
                output.writeBoolean(cursor.categoryId() != null);
                if (cursor.categoryId() != null) {
                    output.writeLong(cursor.categoryId());
                }
                output.writeLong(cursor.publishedAt().getEpochSecond());
                output.writeInt(cursor.publishedAt().getNano());
                output.writeLong(cursor.feedId().getMostSignificantBits());
                output.writeLong(cursor.feedId().getLeastSignificantBits());
                output.writeInt(cursor.likeCount());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("피드 커서를 만들 수 없습니다.", exception);
        }
    }

    public @Nullable FeedCursor decode(@Nullable String encoded, FeedSort sort, @Nullable Long categoryId) {
        if (encoded == null) {
            return null;
        }
        if (encoded.isBlank() || encoded.length() > MAX_ENCODED_LENGTH) {
            throw new InvalidFeedCursorException();
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(encoded)))) {
            if (input.readInt() != VERSION) {
                throw new InvalidFeedCursorException();
            }
            FeedSort cursorSort = FeedSort.valueOf(input.readUTF());
            Long cursorCategory = input.readBoolean() ? input.readLong() : null;
            long seconds = input.readLong();
            int nanos = input.readInt();
            if (nanos < 0 || nanos >= 1_000_000_000) {
                throw new InvalidFeedCursorException();
            }
            FeedCursor cursor = new FeedCursor(cursorSort, cursorCategory, Instant.ofEpochSecond(seconds, nanos),
                    new UUID(input.readLong(), input.readLong()), input.readInt());
            if (input.available() != 0 || cursorSort != sort || !Objects.equals(cursorCategory, categoryId)) {
                throw new InvalidFeedCursorException();
            }
            return cursor;
        } catch (IOException | IllegalArgumentException | DateTimeException exception) {
            throw new InvalidFeedCursorException();
        }
    }
}
