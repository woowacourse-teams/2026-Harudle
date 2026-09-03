package com.harudle.generation.diary.service.port.dto;

import com.harudle.common.validation.TextValidator;

public record StoryboardGenerationRequest(
        String diaryText,
        String storyboardPromptText
) {

    public StoryboardGenerationRequest {
        diaryText = TextValidator.normalizeRequired(diaryText, "일기 내용은 필수입니다.");
        storyboardPromptText = TextValidator.normalizeRequired(
                storyboardPromptText,
                "스토리보드 프롬프트는 필수입니다."
        );
    }
}
