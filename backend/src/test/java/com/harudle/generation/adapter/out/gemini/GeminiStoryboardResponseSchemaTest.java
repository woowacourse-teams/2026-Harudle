package com.harudle.generation.adapter.out.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiStoryboardResponseSchemaTest {

    @Test
    @DisplayName("스토리보드 응답 스키마는 지원 필드와 명시적인 속성 순서를 사용한다")
    void defineSupportedSchemaWithPropertyOrdering() {
        Map<String, Object> schema = GeminiStoryboardResponseSchema.schema();

        assertThat(schema)
                .containsEntry("type", "object")
                .containsEntry("required", List.of("title", "cast_continuity", "panels", "visual_plan"))
                .containsEntry("propertyOrdering", List.of("title", "cast_continuity", "panels", "visual_plan"))
                .doesNotContainKey("additionalProperties");

        Map<String, Object> properties = mapValue(schema, "properties");
        Map<String, Object> panels = mapValue(properties, "panels");
        assertThat(panels)
                .containsEntry("type", "array")
                .containsEntry("minItems", 4)
                .containsEntry("maxItems", 4);

        Map<String, Object> panelItems = mapValue(panels, "items");
        assertThat(panelItems)
                .containsEntry("required", List.of(
                        "panel_number",
                        "story_role",
                        "caption",
                        "scene",
                        "characters",
                        "emotion",
                        "props"
                ))
                .containsEntry("propertyOrdering", List.of(
                        "panel_number",
                        "story_role",
                        "caption",
                        "scene",
                        "characters",
                        "emotion",
                        "props"
                ))
                .doesNotContainKey("additionalProperties");

        Map<String, Object> panelProperties = mapValue(panelItems, "properties");
        assertThat(mapValue(panelProperties, "story_role"))
                .containsEntry("enum", List.of("setup", "action", "escalation", "resolution"));
        Map<String, Object> caption = mapValue(panelProperties, "caption");
        assertThat(caption)
                .doesNotContainKeys("minLength", "maxLength");
        assertThat((String) caption.get("description"))
                .contains("never more than 24");
        assertThat(mapValue(panelProperties, "props"))
                .containsEntry("maxItems", 3);

        Map<String, Object> visualPlan = mapValue(properties, "visual_plan");
        assertThat(visualPlan)
                .containsEntry("required", List.of("environment_rule", "focal_colors"))
                .containsEntry("propertyOrdering", List.of("environment_rule", "focal_colors"));
        Map<String, Object> visualPlanProperties = mapValue(visualPlan, "properties");
        Map<String, Object> focalColors = mapValue(visualPlanProperties, "focal_colors");
        Map<String, Object> colorTarget = mapValue(focalColors, "items");
        assertThat(colorTarget)
                .containsEntry("required", List.of("prop", "component", "color_hex", "panel_numbers"));
        assertThat(mapValue(mapValue(colorTarget, "properties"), "panel_numbers"))
                .containsEntry("minItems", 1)
                .containsEntry("maxItems", 4);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }
}
