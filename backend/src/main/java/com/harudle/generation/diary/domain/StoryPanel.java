package com.harudle.generation.domain;

import com.harudle.common.validation.TextValidator;
import java.util.List;

public record StoryPanel(
        int panelNumber,
        String caption,
        String scene,
        String characters,
        String emotion,
        List<String> props
) {

    private static final int MIN_PANEL_NUMBER = 1;
    private static final int MAX_CAPTION_LENGTH = 24;
    private static final int MAX_PROP_COUNT = 3;

    public StoryPanel {
        validatePanelNumber(panelNumber);
        caption = normalizeCaption(caption);
        scene = TextValidator.normalizeRequired(scene, "장면은 필수입니다.");
        characters = TextValidator.normalizeRequired(characters, "등장인물은 필수입니다.");
        emotion = TextValidator.normalizeRequired(emotion, "감정은 필수입니다.");
        props = normalizeProps(props);
    }

    private static String normalizeCaption(String caption) {
        String normalized = TextValidator.normalizeRequired(caption, "캡션은 필수입니다.");
        validateCaption(normalized);
        return normalized;
    }

    private static List<String> normalizeProps(List<String> props) {
        validateProps(props);
        return props.stream()
                .map(prop -> TextValidator.normalizeRequired(prop, "소품은 필수입니다."))
                .toList();
    }

    private static void validatePanelNumber(int panelNumber) {
        if (panelNumber < MIN_PANEL_NUMBER || panelNumber > Storyboard.PANEL_COUNT) {
            throw new IllegalArgumentException(
                    "패널 번호는 %d 이상 %d 이하여야 합니다."
                            .formatted(MIN_PANEL_NUMBER, Storyboard.PANEL_COUNT)
            );
        }
    }

    private static void validateCaption(String caption) {
        if (caption.contains("\n") || caption.contains("\r")) {
            throw new IllegalArgumentException("캡션은 한 줄이어야 합니다.");
        }
        if (caption.codePointCount(0, caption.length()) > MAX_CAPTION_LENGTH) {
            throw new IllegalArgumentException("캡션은 %d자 이하여야 합니다.".formatted(MAX_CAPTION_LENGTH));
        }
    }

    private static void validateProps(List<String> props) {
        if (props == null) {
            throw new IllegalArgumentException("소품 목록은 필수입니다.");
        }
        if (props.size() > MAX_PROP_COUNT) {
            throw new IllegalArgumentException(
                    "소품은 최대 %d개까지 지정할 수 있습니다.".formatted(MAX_PROP_COUNT)
            );
        }
    }
}
