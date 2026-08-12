package com.harudle.share.controller.dto;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ShareLinkResponse(
        UUID shareId,
        URI shareUrl,
        OffsetDateTime createdAt
) {
}
