package com.harudle.generation.service;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.repository.GenerationUsageRepository;
import com.harudle.generation.service.exception.DailyGenerationLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class GenerationUsageService {

    private static final long MIN_RETRY_AFTER_SECONDS = 1L;

    private final GenerationUsageRepository generationUsageRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    GenerationUsageService(
            GenerationUsageRepository generationUsageRepository,
            UserRepository userRepository,
            @Qualifier("serviceClock") Clock clock
    ) {
        this.generationUsageRepository = generationUsageRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    public GenerationUsage getTodayUsage(UUID userId) {
        LocalDate usageDate = getUsageDate();
        return generationUsageRepository.find(userId, usageDate)
                .orElseGet(() -> GenerationUsage.empty(usageDate, currentLimitCount(userId)));
    }

    public GenerationUsage incrementTodayUsage(UUID userId) {
        LocalDate usageDate = getUsageDate();
        return generationUsageRepository.tryIncrementWithinLimit(userId, usageDate)
                .orElseThrow(() -> new DailyGenerationLimitExceededException(
                        secondsUntilNextUsageDate(usageDate)
        ));
    }

    public Optional<GenerationUsage> restoreTodayUsage(UUID userId, int restoreCount) {
        if (restoreCount < 1) {
            throw new IllegalArgumentException("복구 횟수는 1 이상이어야 합니다.");
        }
        return generationUsageRepository.tryRestore(userId, getUsageDate(), restoreCount);
    }

    public void updateTodayLimit(UUID userId, int limitCount) {
        if (limitCount < 0) {
            throw new IllegalArgumentException("일일 생성 한도는 0 이상이어야 합니다.");
        }
        generationUsageRepository.updateLimitCount(userId, getUsageDate(), limitCount);
    }

    public GenerationUsage resetTodayUsage(UUID userId, int currentLimitCount) {
        LocalDate usageDate = getUsageDate();
        return generationUsageRepository.tryReset(userId, usageDate)
                .orElseGet(() -> GenerationUsage.empty(usageDate, currentLimitCount));
    }

    private LocalDate getUsageDate() {
        return LocalDate.now(clock);
    }

    private int currentLimitCount(UUID userId) {
        return userRepository.findById(userId)
                .map(User::getDailyGenerationLimit)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
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
