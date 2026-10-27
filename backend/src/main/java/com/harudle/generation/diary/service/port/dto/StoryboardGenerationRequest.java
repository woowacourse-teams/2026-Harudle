package com.harudle.generation.diary.service.port.dto;

import com.harudle.common.validation.TextValidator;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext;
import java.util.Objects;
import java.util.Optional;

public record StoryboardGenerationRequest(
        String diaryText,
        String storyboardPromptText,
        Optional<StoryboardAttemptContext> attemptContext
) {

    public StoryboardGenerationRequest(String diaryText, String storyboardPromptText) {
        this(diaryText, storyboardPromptText, Optional.empty());
    }

    public StoryboardGenerationRequest {
        diaryText = TextValidator.normalizeRequired(diaryText, "일기 내용은 필수입니다.");
        storyboardPromptText = TextValidator.normalizeRequired(
                storyboardPromptText,
                "스토리보드 프롬프트는 필수입니다."
        );
        Objects.requireNonNull(attemptContext, "스토리보드 호출 컨텍스트 선택값은 필수입니다.");
    }

    public StoryboardGenerationRequest withAttemptContext(StoryboardAttemptContext context) {
        return new StoryboardGenerationRequest(diaryText, storyboardPromptText, Optional.of(context));
    }
}
