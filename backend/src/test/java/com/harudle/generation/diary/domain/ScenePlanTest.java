package com.harudle.generation.diary.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScenePlanTest {

    @Test
    @DisplayName("같은 소품 부분에 두 색상을 배정할 수 없다")
    void rejectDuplicateColorTarget() {
        assertThatThrownBy(() -> new ScenePlan(
                "Keep the same place.",
                List.of(
                        new FocalColor("umbrella", "canopy", "#A99BE8", List.of(2)),
                        new FocalColor("Umbrella", "Canopy", "#79B8AD", List.of(2))
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복 지정");
    }
}
