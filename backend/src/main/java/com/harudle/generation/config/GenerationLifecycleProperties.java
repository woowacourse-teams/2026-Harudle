package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.lifecycle")
public record GenerationLifecycleProperties(
        @NotNull Duration processingTimeout,
        @NotNull Duration cleanupInterval,
        @NotNull Duration pendingTimeout,
        @NotNull Duration pendingCleanupInterval
) {

    @AssertTrue(message = "그림일기 생성 처리 제한 시간은 양수여야 합니다.")
    public boolean isProcessingTimeoutPositive() {
        return isPositive(processingTimeout);
    }

    @AssertTrue(message = "그림일기 생성 정리 주기는 양수여야 합니다.")
    public boolean isCleanupIntervalPositive() {
        return isPositive(cleanupInterval);
    }

    @AssertTrue(message = "그림일기 생성 대기 제한 시간은 양수여야 합니다.")
    public boolean isPendingTimeoutPositive() {
        return isPositive(pendingTimeout);
    }

    @AssertTrue(message = "그림일기 생성 대기 작업 정리 주기는 1ms 이상이어야 합니다.")
    public boolean isPendingCleanupIntervalAtLeastOneMillisecond() {
        return pendingCleanupInterval != null
                && pendingCleanupInterval.compareTo(Duration.ofMillis(1)) >= 0;
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
