package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.orphan-image-cleanup")
public record OrphanImageCleanupProperties(
        @DefaultValue("1h") @NotNull Duration minimumAge,
        @DefaultValue("10m") @NotNull Duration interval
) {
    @AssertTrue(message = "고아 이미지 정리 유예 시간과 주기는 양수여야 합니다.")
    public boolean isDurationValid() {
        return minimumAge != null && !minimumAge.isNegative() && !minimumAge.isZero()
                && interval != null && !interval.isNegative() && !interval.isZero();
    }
}
