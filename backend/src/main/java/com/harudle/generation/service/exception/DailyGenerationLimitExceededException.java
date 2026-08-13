package com.harudle.generation.service.exception;

import java.io.Serial;

public final class DailyGenerationLimitExceededException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;
    private static final long MIN_RETRY_AFTER_SECONDS = 1L;

    private final long retryAfterSeconds;

    public DailyGenerationLimitExceededException(long retryAfterSeconds) {
        super("오늘 생성 가능한 횟수를 모두 사용했습니다.");
        if (retryAfterSeconds < MIN_RETRY_AFTER_SECONDS) {
            throw new IllegalArgumentException("재시도 대기 시간은 양수여야 합니다.");
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
