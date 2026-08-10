package com.harudle.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StoryboardGenerationRequestTest {

    @Test
    @DisplayName("스토리보드 생성 요청의 문자열 앞뒤 공백을 제거한다")
    void createStoryboardGenerationRequest() {
        StoryboardGenerationRequest request = new StoryboardGenerationRequest(
                " 오늘 친구와 카페에 갔다. ",
                " 네 컷 스토리보드로 변환한다. "
        );

        assertThat(request.diaryText()).isEqualTo("오늘 친구와 카페에 갔다.");
        assertThat(request.storyboardPromptText()).isEqualTo("네 컷 스토리보드로 변환한다.");
    }

    @Test
    @DisplayName("일기 내용이 비어 있으면 스토리보드 생성을 요청할 수 없다")
    void rejectBlankDiaryText() {
        assertThatThrownBy(() -> new StoryboardGenerationRequest(
                " ",
                "네 컷 스토리보드로 변환한다."
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("일기 내용");
    }

    @Test
    @DisplayName("스토리보드 프롬프트가 비어 있으면 생성을 요청할 수 없다")
    void rejectBlankStoryboardPromptText() {
        assertThatThrownBy(() -> new StoryboardGenerationRequest(
                "오늘 친구와 카페에 갔다.",
                " "
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("스토리보드 프롬프트");
    }
}
