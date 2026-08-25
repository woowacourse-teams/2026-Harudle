package com.harudle.admin.repository;

import java.time.LocalDate;
import java.util.UUID;

public interface AdminUserUsageCommandRepository {
    void changeDailyGenerationLimit(UUID userId, int limitCount, LocalDate usageDate);

    void changeUsedCount(UUID userId, int usedCount, LocalDate usageDate);
}
