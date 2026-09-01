package com.harudle.generation.presentation;

import com.harudle.generation.domain.GenerationUsage;
import java.time.LocalDate;

public record GenerationUsageResponse(
        LocalDate usageDate,
        int usedCount,
        int limitCount,
        int remainingCount
) {

    public static GenerationUsageResponse from(GenerationUsage usage) {
        return new GenerationUsageResponse(
                usage.usageDate(),
                usage.usedCount(),
                usage.limitCount(),
                usage.remainingCount()
        );
    }
}
