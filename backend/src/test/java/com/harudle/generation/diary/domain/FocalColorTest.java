package com.harudle.generation.diary.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FocalColorTest {

    @Test
    @DisplayName("색상 대상은 명확한 부분, 색상 코드, 순서 있는 패널 번호를 보존한다")
    void createFocalColor() {
        List<Integer> panelNumbers = new ArrayList<>(List.of(2, 3));

        FocalColor color = new FocalColor(" umbrella ", " canopy ", "#A99BE8", panelNumbers);
        panelNumbers.clear();

        assertThat(color.prop()).isEqualTo("umbrella");
        assertThat(color.component()).isEqualTo("canopy");
        assertThat(color.panelNumbers()).containsExactly(2, 3);
    }

    @Test
    @DisplayName("잘못된 색상 코드와 중복 패널 번호는 거부한다")
    void rejectInvalidColorTarget() {
        assertThatThrownBy(() -> new FocalColor("umbrella", "canopy", "lavender", List.of(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#RRGGBB");
        assertThatThrownBy(() -> new FocalColor("umbrella", "canopy", "#A99BE8", List.of(2, 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복 없이");
    }
}
