package com.harudle.generation.adapter.out.storyboard;

import com.harudle.generation.diary.domain.FocalColor;
import com.harudle.generation.diary.domain.ScenePlan;
import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import java.util.List;
import java.util.stream.IntStream;

public final class StoryboardResponseMapper {

    private static final List<String> STORY_ROLES = List.of(
            "setup",
            "action",
            "escalation",
            "resolution"
    );

    public Storyboard map(StoryboardResponse response) {
        validateResponse(response);
        validatePanels(response.panels());

        List<StoryPanel> panels = mapPanels(response.panels());
        validateStoryRoleOrder(response.panels());
        return new Storyboard(response.title(), response.castContinuity(), panels,
                mapVisualPlan(response.visualPlan()));
    }

    private static ScenePlan mapVisualPlan(StoryboardResponse.VisualPlan visualPlan) {
        if (visualPlan == null || visualPlan.focalColors() == null) {
            throw new IllegalArgumentException("스토리보드 시각 계획은 필수입니다.");
        }
        if (visualPlan.focalColors().stream().anyMatch(color -> color == null)) {
            throw new IllegalArgumentException("스토리보드 색상 계획에 null을 포함할 수 없습니다.");
        }
        List<FocalColor> focalColors = visualPlan.focalColors().stream()
                .map(color -> new FocalColor(
                        color.prop(), color.component(), color.colorHex(), color.panelNumbers()
                ))
                .toList();
        return new ScenePlan(visualPlan.environmentRule(), focalColors);
    }

    private static List<StoryPanel> mapPanels(List<StoryboardResponse.Panel> panels) {
        return panels.stream()
                .map(StoryboardResponseMapper::mapPanel)
                .toList();
    }

    private static StoryPanel mapPanel(StoryboardResponse.Panel panel) {
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

    private static void validateResponse(StoryboardResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("스토리보드 응답은 필수입니다.");
        }
    }

    private static void validatePanels(List<StoryboardResponse.Panel> panels) {
        if (panels == null) {
            throw new IllegalArgumentException("스토리보드 패널 목록은 필수입니다.");
        }
        if (panels.size() != STORY_ROLES.size()) {
            throw new IllegalArgumentException("스토리보드는 정확히 4개의 패널이 필요합니다.");
        }
    }

    private static void validatePanel(StoryboardResponse.Panel panel) {
        if (panel == null) {
            throw new IllegalArgumentException("스토리보드 패널은 필수입니다.");
        }
    }

    private static void validateStoryRoleOrder(List<StoryboardResponse.Panel> panels) {
        IntStream.range(0, STORY_ROLES.size()).forEach(index -> {
            StoryboardResponse.Panel panel = panels.get(index);
            String expectedStoryRole = STORY_ROLES.get(index);
            if (!expectedStoryRole.equals(panel.storyRole())) {
                throw new IllegalArgumentException(
                        "스토리보드 역할은 setup, action, escalation, resolution 순서여야 합니다."
                );
            }
        });
    }
}
