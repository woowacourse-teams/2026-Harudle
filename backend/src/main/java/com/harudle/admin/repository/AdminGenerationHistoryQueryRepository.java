package com.harudle.admin.repository;

import com.harudle.admin.query.AdminGenerationHistoryPage;
import com.harudle.generation.domain.GenerationStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AdminGenerationHistoryQueryRepository {

    AdminGenerationHistoryPage search(
            Optional<UUID> userId,
            Optional<GenerationStatus> status,
            Optional<Instant> fromInclusive,
            Optional<Instant> toExclusive,
            int page,
            int size
    );
}
