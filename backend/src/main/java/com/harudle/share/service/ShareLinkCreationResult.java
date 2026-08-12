package com.harudle.share.service;

import java.time.Instant;
import java.util.UUID;

public record ShareLinkCreationResult(
        UUID shareId,
        Instant createdAt,
        boolean created
) {
}
