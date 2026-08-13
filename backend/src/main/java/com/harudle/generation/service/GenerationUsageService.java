package com.harudle.generation.service;

import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.repository.GenerationUsageRepository;
import com.harudle.generation.service.exception.DailyGenerationLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class GenerationUsageService {

    private static final long MIN_RETRY_AFTER_SECONDS = 1L;

    private final GenerationUsageRepository generationUsageRepository;
    private final Clock clock;

    GenerationUsageService(
            GenerationUsageRepository generationUsageRepository,
            @Qualifier("serviceClock") Clock clock
    ) {
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
        return generationUsageRepository.tryIncrementWithinLimit(userId, usageDate)
                .orElseThrow(() -> new DailyGenerationLimitExceededException(
                        secondsUntilNextUsageDate(usageDate)
                ));
    }

    private LocalDate getUsageDate() {
        return LocalDate.now(clock);
    }

    private long secondsUntilNextUsageDate(LocalDate usageDate) {
        Instant now = clock.instant();
        Instant nextUsageDate = usageDate
                .plusDays(1)
                .atStartOfDay(clock.getZone())
                .toInstant();
        return Math.max(MIN_RETRY_AFTER_SECONDS, Duration.between(now, nextUsageDate).toSeconds());
    }
}
