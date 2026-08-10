package com.harudle.generation.domain;

import com.harudle.common.validation.TextValidator;
import java.util.List;

public record Storyboard(
        String title,
        String castContinuity,
        List<StoryPanel> panels
) {

    static final int PANEL_COUNT = 4;
    static final int MAX_TITLE_LENGTH = 100;

    public Storyboard {
        title = normalizeTitle(title);
        castContinuity = TextValidator.normalizeRequired(
                castContinuity,
                "등장인물 연속성은 필수입니다."
        );
        panels = copyAndValidatePanels(panels);
    }

    private static String normalizeTitle(String title) {
        String normalized = TextValidator.normalizeRequired(title, "제목은 필수입니다.");
        validateTitle(normalized);
        return normalized;
    }

    private static List<StoryPanel> copyAndValidatePanels(List<StoryPanel> panels) {
        validatePanelCount(panels);
        List<StoryPanel> copiedPanels = List.copyOf(panels);
        validatePanelOrder(copiedPanels);
        validateDistinctCaptions(copiedPanels);
        return copiedPanels;
    }

    private static void validateTitle(String title) {
        if (title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("제목은 %d자 이하여야 합니다.".formatted(MAX_TITLE_LENGTH));
        }
    }

    private static void validatePanelCount(List<StoryPanel> panels) {
        if (panels == null || panels.size() != PANEL_COUNT) {
            throw new IllegalArgumentException(
                    "스토리보드는 정확히 %d개의 패널로 구성되어야 합니다.".formatted(PANEL_COUNT)
            );
        }
    }

    private static void validatePanelOrder(List<StoryPanel> panels) {
        for (int index = 0; index < panels.size(); index++) {
            StoryPanel panel = panels.get(index);
            int expectedPanelNumber = index + 1;

            if (panel.panelNumber() != expectedPanelNumber) {
                String message = "패널 번호는 1부터 %d까지 순서대로 배치되어야 합니다."
                        .formatted(PANEL_COUNT);
                throw new IllegalArgumentException(message);
            }
        }
    }

    private static void validateDistinctCaptions(List<StoryPanel> panels) {
        long distinctCaptionCount = panels.stream()
                .map(StoryPanel::caption)
                .distinct()
                .count();

        if (distinctCaptionCount != PANEL_COUNT) {
            throw new IllegalArgumentException("각 패널의 캡션은 서로 달라야 합니다.");
        }
    }
}
