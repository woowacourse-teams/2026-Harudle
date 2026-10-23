package com.harudle.generation.usage.domain;

import java.time.LocalDate;

public record GenerationUsage(
        LocalDate usageDate,
        int usedCount,
        int limitCount
) {

    public static final int DEFAULT_LIMIT_COUNT = 3;

    public GenerationUsage {
        validateUsageDate(usageDate);
        validateCounts(usedCount, limitCount);
    }

    public static GenerationUsage empty(LocalDate usageDate) {
        return empty(usageDate, DEFAULT_LIMIT_COUNT);
    }

    public static GenerationUsage empty(LocalDate usageDate, int limitCount) {
        return new GenerationUsage(usageDate, 0, limitCount);
    }

    public int remainingCount() {
        return limitCount - usedCount;
    }

    private static void validateUsageDate(LocalDate usageDate) {
        if (usageDate == null) {
            throw new IllegalArgumentException("생성 사용일은 필수입니다.");
        }
    }

    private static void validateCounts(int usedCount, int limitCount) {
        if (usedCount < 0) {
            throw new IllegalArgumentException("사용 횟수는 0 이상이어야 합니다.");
        }
        if (limitCount < 1) {
            throw new IllegalArgumentException("제한 횟수는 1 이상이어야 합니다.");
        }
        if (usedCount > limitCount) {
            throw new IllegalArgumentException("사용 횟수는 제한 횟수를 초과할 수 없습니다.");
        }
    }
}
