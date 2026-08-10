package com.harudle.generation.repository;

import com.harudle.generation.domain.GenerationUsage;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface GenerationUsageRepository {

    Optional<GenerationUsage> find(UUID userId, LocalDate usageDate);

    Optional<GenerationUsage> incrementWithinLimit(UUID userId, LocalDate usageDate);
}
