package com.harudle.block.repository;

import java.time.Instant;
import java.util.UUID;

public record BlockSnapshot(UUID id, UUID blockedId, Instant createdAt, boolean inactiveTarget) {
}
