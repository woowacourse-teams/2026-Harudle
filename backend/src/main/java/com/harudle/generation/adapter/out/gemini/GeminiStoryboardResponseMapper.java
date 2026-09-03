package com.harudle.generation.adapter.out.gemini;

import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import java.util.List;
import java.util.stream.IntStream;

public final class GeminiStoryboardResponseMapper {

    private static final List<String> STORY_ROLES = List.of(
            "setup",
            "action",
            "escalation",
            "resolution"
    );

    public Storyboard map(GeminiStoryboardResponse response) {
        validateResponse(response);
        validatePanels(response.panels());

        List<StoryPanel> panels = mapPanels(response.panels());
        Storyboard storyboard = new Storyboard(response.title(), response.castContinuity(), panels);
        validateStoryRoleOrder(response.panels());
        return storyboard;
    }

    private static List<StoryPanel> mapPanels(List<GeminiStoryboardResponse.Panel> panels) {
        return panels.stream()
                .map(GeminiStoryboardResponseMapper::mapPanel)
                .toList();
    }

    private static StoryPanel mapPanel(GeminiStoryboardResponse.Panel panel) {
        validatePanel(panel);

        return new StoryPanel(
                panel.panelNumber(),
                panel.caption(),
                panel.scene(),
                panel.characters(),
                panel.emotion(),
                panel.props()
        );
    }

    private static void validateResponse(GeminiStoryboardResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("Gemini 스토리보드 응답은 필수입니다.");
        }
    }

    private static void validatePanels(List<GeminiStoryboardResponse.Panel> panels) {
        if (panels == null) {
            throw new IllegalArgumentException("Gemini 스토리보드 패널 목록은 필수입니다.");
        }
    }

    private static void validatePanel(GeminiStoryboardResponse.Panel panel) {
        if (panel == null) {
            throw new IllegalArgumentException("Gemini 스토리보드 패널은 필수입니다.");
        }
    }

    private static void validateStoryRoleOrder(List<GeminiStoryboardResponse.Panel> panels) {
        IntStream.range(0, STORY_ROLES.size()).forEach(index -> {
            GeminiStoryboardResponse.Panel panel = panels.get(index);
            String expectedStoryRole = STORY_ROLES.get(index);
            if (!expectedStoryRole.equals(panel.storyRole())) {
                throw new IllegalArgumentException(
                        "Gemini 스토리보드 역할은 setup, action, escalation, resolution 순서여야 합니다."
                );
            }
        });
    }
}
