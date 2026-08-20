package com.harudle.diary.presentation;

import com.harudle.common.validation.CanonicalUuidParser;
import java.util.UUID;

final class IdempotencyKeyParser {

    private IdempotencyKeyParser() {
    }

    static UUID parse(String idempotencyKey) {
        return CanonicalUuidParser.parse(idempotencyKey)
                .orElseThrow(InvalidIdempotencyKeyException::new);
    }
}
