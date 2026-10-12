package com.harudle.generation.adapter.out.qwen;

import java.time.Duration;
import java.util.Objects;

public record QwenStoryboardSettings(
        String model,
        int maxOutputTokens,
        boolean thinking,
        Integer thinkingBudget,
        boolean strictSchema,
        Duration requestTimeout
) {

    public QwenStoryboardSettings {
        Objects.requireNonNull(requestTimeout, "Qwen 제한 시간은 필수입니다.");
        if (model == null || model.isBlank() || maxOutputTokens < 1
                || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Qwen 모델, 출력 한도, 제한 시간을 확인해 주세요.");
        }
        if (thinkingBudget != null && (!thinking || thinkingBudget < 1)) {
            throw new IllegalArgumentException("추론 예산은 추론이 활성화된 경우에 양수로 지정해야 합니다.");
        }
    }

    public static QwenStoryboardSettings defaults() {
        return new QwenStoryboardSettings("qwen3.8-max", 8192, true, 1024, false, Duration.ofSeconds(180));
    }
}
