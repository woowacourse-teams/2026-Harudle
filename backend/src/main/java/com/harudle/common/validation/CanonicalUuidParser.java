package com.harudle.common.validation;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CanonicalUuidParser {

    private static final Pattern CANONICAL_UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private CanonicalUuidParser() {
    }

    public static Optional<UUID> parse(String value) {
        return Optional.ofNullable(value)
                .filter(CANONICAL_UUID_PATTERN.asMatchPredicate())
                .map(UUID::fromString);
    }
}
