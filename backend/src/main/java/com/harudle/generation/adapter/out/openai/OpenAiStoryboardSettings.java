package com.harudle.generation.adapter.out.openai;

import java.time.Duration;
import java.util.Objects;

/**
 * OpenAI 스토리보드 요청 설정.
 * 공통 응답 스키마가 OpenAI 엄격 모드의 조건(모든 객체의 additionalProperties=false)을 만족하지 않으므로
 * 기본값은 JSON 객체 응답과 프롬프트 내 스키마 안내를 사용한다.
 */
public record OpenAiStoryboardSettings(
        String model,
        int maxOutputTokens,
        String reasoningEffort,
        boolean strictSchema,
        Duration requestTimeout
) {

    public OpenAiStoryboardSettings {
        Objects.requireNonNull(requestTimeout, "OpenAI 제한 시간은 필수입니다.");
        if (model == null || model.isBlank() || maxOutputTokens < 1
                || reasoningEffort != null && reasoningEffort.isBlank()
                || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("OpenAI 모델, 출력 한도, 추론 강도, 제한 시간을 확인해 주세요.");
        }
    }

    public static OpenAiStoryboardSettings defaults() {
        return new OpenAiStoryboardSettings("gpt-6-luna", 8192, "medium", false, Duration.ofSeconds(180));
    }
}
