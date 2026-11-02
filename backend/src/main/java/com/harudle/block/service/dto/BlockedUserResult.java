package com.harudle.block.service.dto;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record BlockedUserResult(UUID userId, String nickname, @Nullable URI profileImageUrl, Instant blockedAt) {
}
