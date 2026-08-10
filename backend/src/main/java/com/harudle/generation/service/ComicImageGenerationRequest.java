package com.harudle.generation.service;

import com.harudle.generation.domain.Storyboard;

public record ComicImageGenerationRequest(
        Storyboard storyboard,
        String imageStylePromptText,
        ReferenceImage referenceImage
) {

    public ComicImageGenerationRequest {
        validateStoryboard(storyboard);
        imageStylePromptText = normalizeImageStylePromptText(imageStylePromptText);
        validateReferenceImage(referenceImage);
    }

    private static String normalizeImageStylePromptText(String imageStylePromptText) {
        validateImageStylePromptText(imageStylePromptText);
        return imageStylePromptText.strip();
    }

    private static void validateStoryboard(Storyboard storyboard) {
        if (storyboard == null) {
            throw new IllegalArgumentException("스토리보드는 필수입니다.");
        }
    }

    private static void validateReferenceImage(ReferenceImage referenceImage) {
        if (referenceImage == null) {
            throw new IllegalArgumentException("참조 이미지는 필수입니다.");
        }
    }

    private static void validateImageStylePromptText(String imageStylePromptText) {
        if (imageStylePromptText == null || imageStylePromptText.isBlank()) {
            throw new IllegalArgumentException("이미지 스타일 프롬프트는 필수입니다.");
        }
    }
}
