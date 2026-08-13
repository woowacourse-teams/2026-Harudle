package com.harudle.share.service;

import com.harudle.generation.domain.GenerationStatus;
import java.util.UUID;

public record ShareLinkCreationInfo(
        UUID generationId,
        GenerationStatus generationStatus
) {
}
