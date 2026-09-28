package com.harudle.generation.adapter.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.diary.domain.Storyboard;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiStoryboardResponseMapperTest {

    private final GeminiStoryboardResponseMapper mapper = new GeminiStoryboardResponseMapper();

    @Test
    @DisplayName("Gemini 응답을 스토리보드로 변환한다")
    void mapStoryboardResponse() {
        GeminiStoryboardResponse response = createResponse(createPanels());

        Storyboard storyboard = mapper.map(response);

        assertThat(storyboard.title()).isEqualTo("인스타 맛집의 함정");
        assertThat(storyboard.castContinuity()).isEqualTo("One recurring protagonist.");
        assertThat(storyboard.panels()).hasSize(4);
        assertThat(storyboard.panels().get(0).panelNumber()).isEqualTo(1);
        assertThat(storyboard.panels().get(0).caption()).isEqualTo("캡션 1");
        assertThat(storyboard.panels().get(3).panelNumber()).isEqualTo(4);
        assertThat(storyboard.panels().get(3).caption()).isEqualTo("캡션 4");
        assertThat(storyboard.scenePlan().environmentRule()).isEqualTo("Keep the same location.");
        assertThat(storyboard.scenePlan().focalColors()).singleElement().satisfies(color -> {
            assertThat(color.prop()).isEqualTo("Prop 2");
            assertThat(color.component()).isEqualTo("cover");
            assertThat(color.colorHex()).isEqualTo("#A99BE8");
            assertThat(color.panelNumbers()).containsExactly(2);
        });
    }

    @Test
    @DisplayName("Gemini 응답의 이야기 역할이 정해진 순서와 다르면 변환할 수 없다")
    void rejectInvalidStoryRoleOrder() {
        List<GeminiStoryboardResponse.Panel> panels = new ArrayList<>(createPanels());
        panels.set(1, createPanel(2, "resolution", "캡션 2"));
        GeminiStoryboardResponse response = createResponse(panels);

        assertThatThrownBy(() -> mapper.map(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setup, action, escalation, resolution");
    }

    @Test
    @DisplayName("새 Gemini 응답에 시각 계획이 빠지면 변환할 수 없다")
    void rejectMissingVisualPlan() {
        GeminiStoryboardResponse response = new GeminiStoryboardResponse(
                "제목", "One recurring protagonist.", createPanels(), null
        );

        assertThatThrownBy(() -> mapper.map(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시각 계획");
    }

    @Test
    @DisplayName("Gemini 색상 계획 목록에 null 요소가 있으면 잘못된 응답으로 거부한다")
    void rejectNullColorTarget() {
        List<GeminiStoryboardResponse.ColorTarget> focalColors = new ArrayList<>();
        focalColors.add(null);
        GeminiStoryboardResponse response = new GeminiStoryboardResponse(
                "제목",
                "One recurring protagonist.",
                createPanels(),
                new GeminiStoryboardResponse.VisualPlan("Keep the same location.", focalColors)
        );

        assertThatThrownBy(() -> mapper.map(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("색상 계획");
    }

    private GeminiStoryboardResponse createResponse(List<GeminiStoryboardResponse.Panel> panels) {
        return new GeminiStoryboardResponse(
                "인스타 맛집의 함정",
                "One recurring protagonist.",
                panels,
                new GeminiStoryboardResponse.VisualPlan(
                        "Keep the same location.",
                        List.of(new GeminiStoryboardResponse.ColorTarget("Prop 2", "cover", "#A99BE8", List.of(2)))
                )
        );
    }

    private List<GeminiStoryboardResponse.Panel> createPanels() {
        return List.of(
                createPanel(1, "setup", "캡션 1"),
                createPanel(2, "action", "캡션 2"),
                createPanel(3, "escalation", "캡션 3"),
                createPanel(4, "resolution", "캡션 4")
        );
    }

    private GeminiStoryboardResponse.Panel createPanel(
            int panelNumber,
            String storyRole,
            String caption
    ) {
        return new GeminiStoryboardResponse.Panel(
                panelNumber,
                storyRole,
                caption,
                "Scene " + panelNumber,
                "Characters " + panelNumber,
                "Emotion " + panelNumber,
                List.of("Prop " + panelNumber)
        );
    }
}
