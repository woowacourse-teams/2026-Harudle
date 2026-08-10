package com.harudle.generation.service;

import static com.harudle.common.config.TimeConfiguration.SERVICE_ZONE_ID;

import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.repository.GenerationUsageRepository;
import com.harudle.generation.service.exception.DailyGenerationLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GenerationUsageService {

    private final GenerationUsageRepository generationUsageRepository;
    private final Clock clock;

    public GenerationUsageService(GenerationUsageRepository generationUsageRepository, Clock clock) {
        this.generationUsageRepository = generationUsageRepository;
        this.clock = clock;
    }

    public GenerationUsage getTodayUsage(UUID userId) {
        LocalDate usageDate = getUsageDate();
        return generationUsageRepository.find(userId, usageDate)
                .orElseGet(() -> GenerationUsage.empty(usageDate));
    }

    public GenerationUsage incrementTodayUsage(UUID userId) {
        LocalDate usageDate = getUsageDate();
        return generationUsageRepository.incrementWithinLimit(userId, usageDate)
                .orElseThrow(() -> new DailyGenerationLimitExceededException(secondsUntilNextUsageDate()));
    }

    private LocalDate getUsageDate() {
        return LocalDate.now(clock.withZone(SERVICE_ZONE_ID));
    }

    private long secondsUntilNextUsageDate() {
        Instant now = clock.instant();
        Instant nextUsageDate = getUsageDate()
                .plusDays(1)
                .atStartOfDay(SERVICE_ZONE_ID)
                .toInstant();
        return Math.max(1, Duration.between(now, nextUsageDate).toSeconds());
    }
}
