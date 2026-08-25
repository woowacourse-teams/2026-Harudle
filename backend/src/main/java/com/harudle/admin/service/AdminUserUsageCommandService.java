package com.harudle.admin.service;

import com.harudle.admin.repository.AdminUserUsageCommandRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserUsageCommandService {
    private final AdminUserUsageCommandRepository repository;
    private final Clock clock;

    public AdminUserUsageCommandService(AdminUserUsageCommandRepository repository,
                                        @Qualifier("serviceClock") Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void changeDailyGenerationLimit(UUID userId, int limitCount) {
        repository.changeDailyGenerationLimit(userId, limitCount, LocalDate.now(clock));
    }
}
