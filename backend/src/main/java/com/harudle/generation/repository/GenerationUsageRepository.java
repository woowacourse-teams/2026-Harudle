package com.harudle.generation.repository;

import com.harudle.generation.domain.GenerationUsage;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface GenerationUsageRepository {

    Optional<GenerationUsage> find(UUID userId, LocalDate usageDate);

    Optional<GenerationUsage> tryIncrementWithinLimit(UUID userId, LocalDate usageDate);

    int updateLimitCount(UUID userId, LocalDate usageDate, int limitCount);

    Optional<GenerationUsage> tryRestore(UUID userId, LocalDate usageDate, int restoreCount);

    Optional<GenerationUsage> tryReset(UUID userId, LocalDate usageDate);
}
