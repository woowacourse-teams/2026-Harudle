package com.harudle.generation.adapter.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GeminiStoryboardResponseTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("snake_case JSON을 Gemini 스토리보드 응답으로 변환한다")
    void deserializeSnakeCaseResponse() throws Exception {
        String responseJson = """
                {
                  "title": "인스타 맛집의 함정",
                  "cast_continuity": "One recurring protagonist.",
                  "panels": [
                    {
                      "panel_number": 1,
                      "story_role": "setup",
                      "caption": "와, 침 고인다",
                      "scene": "A person looks at a food photo.",
                      "characters": "The protagonist holds a smartphone.",
                      "emotion": "Visible excitement.",
                      "props": ["smartphone"]
                    }
                  ]
                }
                """;

        GeminiStoryboardResponse response = jsonMapper.readValue(
                responseJson,
                GeminiStoryboardResponse.class
        );

        assertThat(response.title()).isEqualTo("인스타 맛집의 함정");
        assertThat(response.castContinuity()).isEqualTo("One recurring protagonist.");
        assertThat(response.panels()).singleElement().satisfies(panel -> {
            assertThat(panel.panelNumber()).isEqualTo(1);
            assertThat(panel.storyRole()).isEqualTo("setup");
            assertThat(panel.caption()).isEqualTo("와, 침 고인다");
            assertThat(panel.props()).containsExactly("smartphone");
        });
    }
}
