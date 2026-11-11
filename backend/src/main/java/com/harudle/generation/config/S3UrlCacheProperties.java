package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.storage.s3.url-cache")
public record S3UrlCacheProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("500") @Min(1) int maximumSize,
        @DefaultValue("4") @Min(1) int refreshConcurrency,
        @DefaultValue("64") @Min(1) int maximumWaiters,
        @DefaultValue("1s") Duration waitTimeout
) {

    @AssertTrue(message = "S3 주소 갱신 대기 시간은 0초 초과 1분 이하여야 합니다.")
    public boolean isWaitTimeoutValid() {
        return waitTimeout != null && !waitTimeout.isNegative() && !waitTimeout.isZero()
                && waitTimeout.compareTo(Duration.ofMinutes(1)) <= 0;
    }
}
