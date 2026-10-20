package com.harudle.generation.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.worker")
public record GenerationWorkerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1") @Min(value = 1, message = "동시에 실행할 생성 작업 수는 1 이상이어야 합니다.") int concurrency,
        @DefaultValue("5s") @NotNull Duration pollInterval
) {

    @AssertTrue(message = "생성 작업 확인 간격은 1ms 이상이어야 합니다.")
    public boolean isPollIntervalValid() {
        return pollInterval != null && pollInterval.compareTo(Duration.ofMillis(1)) >= 0;
    }
}
