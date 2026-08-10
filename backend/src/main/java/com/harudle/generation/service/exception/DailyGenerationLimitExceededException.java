package com.harudle.generation.service.exception;

import com.harudle.generation.domain.GenerationUsage;
import java.io.Serial;

public final class DailyGenerationLimitExceededException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public DailyGenerationLimitExceededException(long retryAfterSeconds) {
        super("하루 최대 %d번까지 생성할 수 있습니다.".formatted(GenerationUsage.DEFAULT_LIMIT_COUNT));
        if (retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("재시도 대기 시간은 양수여야 합니다.");
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
