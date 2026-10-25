package com.harudle.block.service;

import com.harudle.block.query.BlockCursor;
import com.harudle.block.service.exception.InvalidBlockCursorException;
import com.harudle.common.validation.CanonicalUuidParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class BlockCursorCodec {
    private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");

    public String encode(BlockCursor cursor) {
        String payload = String.join("|", "1", "BLOCKS", cursor.blockerId().toString(),
                cursor.createdAt().toString(), cursor.id().toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<BlockCursor> decode(UUID actorId, @Nullable String encoded) {
        if (encoded == null) {
            return Optional.empty();
        }
        if (encoded.length() > 512 || !BASE64_URL.matcher(encoded).matches()) {
            throw new InvalidBlockCursorException();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
                throw new InvalidBlockCursorException();
            }
            return Optional.of(parse(actorId, new String(bytes, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw new InvalidBlockCursorException();
        }
    }

    private static BlockCursor parse(UUID actorId, String payload) {
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 5 || !"1".equals(fields[0]) || !"BLOCKS".equals(fields[1])) {
            throw new InvalidBlockCursorException();
        }
        UUID owner = CanonicalUuidParser.parse(fields[2]).orElseThrow(InvalidBlockCursorException::new);
        UUID id = CanonicalUuidParser.parse(fields[4]).orElseThrow(InvalidBlockCursorException::new);
        Instant time = Instant.parse(fields[3]);
        int year = time.atOffset(ZoneOffset.UTC).getYear();
        if (!actorId.equals(owner) || year < 1 || year > 9999 || time.getNano() % 1000 != 0) {
            throw new InvalidBlockCursorException();
        }
        return new BlockCursor(owner, time, id);
    }
}
