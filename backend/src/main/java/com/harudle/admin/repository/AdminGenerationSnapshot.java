package com.harudle.admin.repository;

import com.harudle.generation.domain.GenerationStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminGenerationSnapshot(UUID id, UUID userId, String userName, String email,
                                      Instant requestedAt, GenerationStatus status,
                                      Instant completedAt, String errorCode) {}
