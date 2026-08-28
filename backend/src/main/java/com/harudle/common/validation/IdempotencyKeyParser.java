package com.harudle.common.validation;

import java.util.UUID;

public final class IdempotencyKeyParser {

    private IdempotencyKeyParser() {
    }

    public static UUID parse(String idempotencyKey) {
        return CanonicalUuidParser.parse(idempotencyKey)
                .orElseThrow(InvalidIdempotencyKeyException::new);
    }
}
