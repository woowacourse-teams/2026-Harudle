package com.harudle.generation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoryPanelTest {

    @Test
    @DisplayName("스토리 패널을 생성하고 문자열을 정규화한다")
    void createStoryPanel() {
        List<String> props = new ArrayList<>(List.of(" smartphone ", " table "));

        StoryPanel panel = new StoryPanel(
                1,
                " 오늘은 기대돼 ",
                " 주인공이 휴대전화를 바라본다. ",
                " 주인공 한 명 ",
                " 기대감 ",
                props
        );
        props.clear();

        assertThat(panel.caption()).isEqualTo("오늘은 기대돼");
        assertThat(panel.scene()).isEqualTo("주인공이 휴대전화를 바라본다.");
        assertThat(panel.props()).containsExactly("smartphone", "table");
        assertThatThrownBy(() -> panel.props().add("chair"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5})
    @DisplayName("패널 번호가 1부터 4 사이가 아니면 생성할 수 없다")
    void rejectInvalidPanelNumber(int panelNumber) {
        assertThatThrownBy(() -> createPanel(panelNumber, "캡션", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("패널 번호");
    }

    @Test
    @DisplayName("캡션이 비어 있으면 생성할 수 없다")
    void rejectBlankCaption() {
        assertThatThrownBy(() -> createPanel(1, " ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("캡션");
    }

    @Test
    @DisplayName("캡션이 여러 줄이면 생성할 수 없다")
    void rejectMultilineCaption() {
        assertThatThrownBy(() -> createPanel(1, "첫째 줄\n둘째 줄", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("한 줄");
    }

    @Test
    @DisplayName("캡션이 24자를 초과하면 생성할 수 없다")
    void rejectLongCaption() {
        assertThatThrownBy(() -> createPanel(1, "가".repeat(25), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("24자");
    }

    @Test
    @DisplayName("소품이 세 개를 초과하면 생성할 수 없다")
    void rejectTooManyProps() {
        assertThatThrownBy(() -> createPanel(1, "캡션", List.of("a", "b", "c", "d")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최대 3개");
    }

    @Test
    @DisplayName("소품 이름이 비어 있으면 생성할 수 없다")
    void rejectBlankProp() {
        assertThatThrownBy(() -> createPanel(1, "캡션", List.of("smartphone", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("소품");
    }

    private StoryPanel createPanel(int panelNumber, String caption, List<String> props) {
        return new StoryPanel(
                panelNumber,
                caption,
                "주인공이 휴대전화를 바라본다.",
                "주인공 한 명",
                "기대감",
                props
        );
    }
}
