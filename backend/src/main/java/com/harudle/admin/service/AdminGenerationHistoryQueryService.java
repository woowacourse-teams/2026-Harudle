package com.harudle.admin.service;

import com.harudle.admin.query.AdminGenerationHistoryPage;
import com.harudle.admin.repository.AdminGenerationHistoryQueryRepository;
import com.harudle.admin.service.exception.AdminGenerationHistoryDateRangeException;
import com.harudle.generation.domain.GenerationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
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
        var result = generationHistoryQueryRepository.search(
                userId,
                status,
                toStartInstant(from),
                toExclusiveInstant(to),
                PageRequest.of(page, size)
        );
        return new AdminGenerationHistoryPage(result.getContent(), result.getTotalElements());
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new AdminGenerationHistoryDateRangeException();
        }
        if (LocalDate.MAX.equals(to)) {
            throw new AdminGenerationHistoryDateRangeException();
        }
    }

    private Instant toStartInstant(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.atStartOfDay(clock.getZone()).toInstant();
    }

    private Instant toExclusiveInstant(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
    }
}
