package com.harudle.generation.adapter.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiStoryboardResponseSchemaTest {

    @Test
    @DisplayName("Gemini 스토리보드 응답 JSON Schema를 정의한다")
    void createStoryboardResponseSchema() {
        Map<String, Object> schema = GeminiStoryboardResponseSchema.schema();

        assertThat(schema)
                .containsEntry("type", "object")
                .containsEntry("additionalProperties", false)
                .containsEntry("required", List.of("title", "cast_continuity", "panels"));

        Map<String, Object> properties = asMap(schema.get("properties"));
        Map<String, Object> panels = asMap(properties.get("panels"));
        assertThat(panels)
                .containsEntry("type", "array")
                .containsEntry("minItems", 4)
                .containsEntry("maxItems", 4);

        Map<String, Object> panel = asMap(panels.get("items"));
        assertThat(panel)
                .containsEntry("additionalProperties", false)
                .containsEntry("required", List.of(
                        "panel_number",
                        "story_role",
                        "caption",
                        "scene",
                        "characters",
                        "emotion",
                        "props"
                ));

        Map<String, Object> panelProperties = asMap(panel.get("properties"));
        assertThat(asMap(panelProperties.get("story_role")))
                .containsEntry("enum", List.of("setup", "action", "escalation", "resolution"));
        assertThat(asMap(panelProperties.get("caption")))
                .containsEntry("minLength", 2)
                .containsEntry("maxLength", 24);
        assertThat(asMap(panelProperties.get("props")))
                .containsEntry("maxItems", 3);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
