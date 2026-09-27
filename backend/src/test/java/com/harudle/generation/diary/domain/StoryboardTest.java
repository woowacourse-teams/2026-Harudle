package com.harudle.generation.diary.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoryboardTest {

    @Test
    @DisplayName("정해진 이야기 흐름을 가진 네 개의 패널로 스토리보드를 생성한다")
    void createStoryboard() {
        List<StoryPanel> panels = new ArrayList<>(validPanels());

        Storyboard storyboard = new Storyboard(
                " 친구와 보낸 하루 ",
                " 같은 주인공이 모든 패널에 등장한다. ",
                panels
        );
        panels.clear();

        assertThat(storyboard.title()).isEqualTo("친구와 보낸 하루");
        assertThat(storyboard.castContinuity()).isEqualTo("같은 주인공이 모든 패널에 등장한다.");
        assertThat(storyboard.panels()).hasSize(4);
        assertThatThrownBy(() -> storyboard.panels().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("패널이 정확히 네 개가 아니면 스토리보드를 생성할 수 없다")
    void rejectInvalidPanelCount() {
        assertThatThrownBy(() -> new Storyboard(
                "제목",
                "등장인물 연속성",
                validPanels().subList(0, 3)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("정확히 4개");
    }

    @Test
    @DisplayName("패널 번호가 순서대로 배치되지 않으면 스토리보드를 생성할 수 없다")
    void rejectInvalidPanelNumberOrder() {
        List<StoryPanel> panels = new ArrayList<>(validPanels());
        panels.set(0, createPanel(2, "첫 번째 캡션"));

        assertThatThrownBy(() -> new Storyboard("제목", "등장인물 연속성", panels))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("패널 번호");
    }

    @Test
    @DisplayName("패널 캡션이 중복되면 스토리보드를 생성할 수 없다")
    void rejectDuplicateCaptions() {
        List<StoryPanel> panels = new ArrayList<>(validPanels());
        panels.set(3, createPanel(4, "첫 번째 캡션"));

        assertThatThrownBy(() -> new Storyboard("제목", "등장인물 연속성", panels))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("서로 달라야");
    }

    @Test
    @DisplayName("제목이 100자를 초과하면 스토리보드를 생성할 수 없다")
    void rejectLongTitle() {
        assertThatThrownBy(() -> new Storyboard(
                "가".repeat(101),
                "등장인물 연속성",
                validPanels()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100자");
    }

    private List<StoryPanel> validPanels() {
        return List.of(
                createPanel(1, "첫 번째 캡션"),
                createPanel(2, "두 번째 캡션"),
                createPanel(3, "세 번째 캡션"),
                createPanel(4, "네 번째 캡션")
        );
    }

    private StoryPanel createPanel(int panelNumber, String caption) {
        return new StoryPanel(
                panelNumber,
                caption,
                "장면 " + panelNumber,
                "등장인물 " + panelNumber,
                "감정 " + panelNumber,
                List.of("소품 " + panelNumber)
        );
    }
}
