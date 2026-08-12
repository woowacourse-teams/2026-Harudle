package com.harudle.generation.domain;

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
    private static final int MAX_PANEL_NUMBER = 4;
    private static final int MAX_CAPTION_LENGTH = 24;
    private static final int MAX_PROP_COUNT = 3;

    public StoryPanel {
        validatePanelNumber(panelNumber);
        caption = normalizeCaption(caption);
        scene = normalizeRequired(scene, "장면");
        characters = normalizeRequired(characters, "등장인물");
        emotion = normalizeRequired(emotion, "감정");
        props = normalizeProps(props);
    }

    private static String normalizeCaption(String caption) {
        String normalized = normalizeRequired(caption, "캡션");
        validateCaption(normalized);
        return normalized;
    }

    private static List<String> normalizeProps(List<String> props) {
        validateProps(props);
        return props.stream()
                .map(prop -> normalizeRequired(prop, "소품"))
                .toList();
    }

    private static String normalizeRequired(String value, String fieldName) {
        validateRequired(value, fieldName);
        return value.strip();
    }

    private static void validatePanelNumber(int panelNumber) {
        if (panelNumber < MIN_PANEL_NUMBER || panelNumber > MAX_PANEL_NUMBER) {
            throw new IllegalArgumentException("패널 번호는 1 이상 4 이하여야 합니다.");
        }
    }

    private static void validateCaption(String caption) {
        if (caption.contains("\n") || caption.contains("\r")) {
            throw new IllegalArgumentException("캡션은 한 줄이어야 합니다.");
        }
        if (caption.codePointCount(0, caption.length()) > MAX_CAPTION_LENGTH) {
            throw new IllegalArgumentException("캡션은 24자 이하여야 합니다.");
        }
    }

    private static void validateProps(List<String> props) {
        if (props == null) {
            throw new IllegalArgumentException("소품 목록은 필수입니다.");
        }
        if (props.size() > MAX_PROP_COUNT) {
            throw new IllegalArgumentException("소품은 최대 3개까지 지정할 수 있습니다.");
        }
    }

    private static void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은(는) 필수입니다.");
        }
    }
}
