package com.harudle.admin.repository;

import com.harudle.generation.domain.GenerationStatus;
import java.time.Instant;
import java.util.UUID;

public interface AdminGenerationQueryRepository {
    AdminGenerationPage search(UUID userId, GenerationStatus status, Instant from, Instant to, int page, int size);
}
