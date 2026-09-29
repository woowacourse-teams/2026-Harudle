package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.image-integrity")
public record ImageIntegrityProperties(
        boolean enabled,
        @NotNull Duration interval,
        @NotNull Duration initialDelay,
        @Min(1) int pageSize,
        @Min(1) int maxChecksPerRun,
        @NotNull Duration minimumAge,
        @NotNull Duration maxRunDuration
) {

    @AssertTrue(message = "이미지 점검 주기와 최대 실행 시간은 양수여야 합니다.")
    public boolean isPositiveDurations() {
        return isPositive(interval) && isPositive(maxRunDuration);
    }

    @AssertTrue(message = "이미지 점검 시작 지연과 최소 생성 경과 시간은 음수일 수 없습니다.")
    public boolean isNonnegativeDurations() {
        return isNonnegative(initialDelay) && isNonnegative(minimumAge);
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private static boolean isNonnegative(Duration duration) {
        return duration != null && !duration.isNegative();
    }
}
