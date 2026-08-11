package com.harudle.generation.adapter.out.gemini;

import java.util.List;
import java.util.Map;

public final class GeminiStoryboardResponseSchema {

    private static final Map<String, Object> SCHEMA = createSchema();

    private GeminiStoryboardResponseSchema() {
    }

    public static Map<String, Object> schema() {
        return SCHEMA;
    }

    private static Map<String, Object> createSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "title", Map.of(
                                "type", "string",
                                "description", "A short internal Korean title for the adapted diary story."
                        ),
                        "cast_continuity", Map.of(
                                "type", "string",
                                "description", "In English, describe the recurring cast and visual continuity."
                        ),
                        "panels", createPanelsSchema()
                ),
                "required", List.of("title", "cast_continuity", "panels")
        );
    }

    private static Map<String, Object> createPanelsSchema() {
        return Map.of(
                "type", "array",
                "minItems", 4,
                "maxItems", 4,
                "items", Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", createPanelPropertiesSchema(),
                        "required", List.of(
                                "panel_number",
                                "story_role",
                                "caption",
                                "scene",
                                "characters",
                                "emotion",
                                "props"
                        )
                )
        );
    }

    private static Map<String, Object> createPanelPropertiesSchema() {
        return Map.of(
                "panel_number", Map.of(
                        "type", "integer",
                        "minimum", 1,
                        "maximum", 4
                ),
                "story_role", Map.of(
                        "type", "string",
                        "enum", List.of("setup", "action", "escalation", "resolution")
                ),
                "caption", Map.of(
                        "type", "string",
                        "minLength", 2,
                        "maxLength", 24,
                        "description", "One short, natural Korean diary caption rendered verbatim in the panel."
                ),
                "scene", Map.of(
                        "type", "string",
                        "description", "In English, describe the setting, visible action, and composition."
                ),
                "characters", Map.of(
                        "type", "string",
                        "description", "In English, describe visible characters, positions, and interactions."
                ),
                "emotion", Map.of(
                        "type", "string",
                        "description", "In English, describe only visible facial and postural emotion."
                ),
                "props", Map.of(
                        "type", "array",
                        "maxItems", 3,
                        "items", Map.of("type", "string"),
                        "description", "Zero to three objects that must visibly appear in the panel."
                )
        );
    }
}
