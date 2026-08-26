package com.harudle.admin.service;

import com.harudle.admin.query.AdminGenerationHistoryPage;
import com.harudle.admin.repository.AdminGenerationHistoryQueryRepository;
import com.harudle.admin.service.exception.AdminGenerationHistoryDateRangeException;
import com.harudle.generation.domain.GenerationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AdminGenerationHistoryQueryService {

    private final AdminGenerationHistoryQueryRepository generationHistoryQueryRepository;
    private final Clock clock;

    public AdminGenerationHistoryQueryService(
            AdminGenerationHistoryQueryRepository generationHistoryQueryRepository,
            @Qualifier("serviceClock") Clock clock
    ) {
        this.generationHistoryQueryRepository = generationHistoryQueryRepository;
        this.clock = clock;
    }

    public AdminGenerationHistoryPage search(
            UUID userId,
            GenerationStatus status,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    ) {
        validateDateRange(from, to);
        return generationHistoryQueryRepository.search(
                Optional.ofNullable(userId),
                Optional.ofNullable(status),
                toStartInstant(from),
                toExclusiveInstant(to),
                page,
                size
        );
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new AdminGenerationHistoryDateRangeException();
        }
    }

    private Optional<Instant> toStartInstant(LocalDate date) {
        return Optional.ofNullable(date)
                .map(value -> value.atStartOfDay(clock.getZone()).toInstant());
    }

    private Optional<Instant> toExclusiveInstant(LocalDate date) {
        return Optional.ofNullable(date)
                .map(value -> value.plusDays(1).atStartOfDay(clock.getZone()).toInstant());
    }
}
