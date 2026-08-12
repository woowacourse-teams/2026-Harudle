package com.harudle.generation.service.port;

import com.harudle.common.validation.TextValidator;
import com.harudle.generation.domain.Storyboard;

public record DiaryImageGenerationRequest(
        Storyboard storyboard,
        String imageStylePromptText,
        ReferenceImage referenceImage
) {

    public DiaryImageGenerationRequest {
        validateStoryboard(storyboard);
        imageStylePromptText = TextValidator.normalizeRequired(
                imageStylePromptText,
                "이미지 스타일 프롬프트는 필수입니다."
        );
        validateReferenceImage(referenceImage);
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
}
