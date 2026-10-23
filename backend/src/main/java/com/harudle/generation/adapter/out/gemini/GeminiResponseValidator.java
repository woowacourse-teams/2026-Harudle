package com.harudle.generation.adapter.out.gemini;

import com.google.genai.types.GenerateContentResponse;
import java.util.Set;

final class GeminiResponseValidator {

    private static final Set<String> BLOCKED_PROMPT_REASONS = Set.of(
            "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "IMAGE_SAFETY", "MODEL_ARMOR"
    );
    private static final Set<String> BLOCKED_FINISH_REASONS = Set.of(
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "LANGUAGE",
            "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION", "MODEL_ARMOR"
    );

    private GeminiResponseValidator() {
    }

    static void validate(GenerateContentResponse response) {
        if (isContentBlocked(response)) {
            throw new IllegalStateException("Gemini 콘텐츠 기준에 따라 생성이 차단되었습니다.");
        }
    }

    static boolean isContentBlocked(GenerateContentResponse response) {
        if (response == null) {
            return false;
        }
        String finishReason = GeminiFailureType.finishReason(response);
        if (finishReason != null && BLOCKED_FINISH_REASONS.contains(finishReason)) {
            return true;
        }
        return response.promptFeedback()
                .flatMap(feedback -> feedback.blockReason())
                .map(reason -> BLOCKED_PROMPT_REASONS.contains(reason.toString()))
                .orElse(false);
    }
}
