package com.harudle.generation.service;

public record StoryboardGenerationRequest(
        String diaryText,
        String storyboardPromptText
) {

    public StoryboardGenerationRequest {
        diaryText = normalizeRequired(diaryText, "일기 내용");
        storyboardPromptText = normalizeRequired(storyboardPromptText, "스토리보드 프롬프트");
    }

    private static String normalizeRequired(String value, String fieldName) {
        validateRequired(value, fieldName);
        return value.strip();
    }

    private static void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
        }
    }
}
