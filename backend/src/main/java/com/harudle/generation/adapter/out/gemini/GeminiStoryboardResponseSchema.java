package com.harudle.generation.adapter.out.gemini;

import java.util.List;
import java.util.Map;

public final class GeminiStoryboardResponseSchema {

    private static final List<String> ROOT_PROPERTY_ORDERING = List.of(
            "title",
            "cast_continuity",
            "panels"
    );
    private static final List<String> PANEL_PROPERTY_ORDERING = List.of(
            "panel_number",
            "story_role",
            "caption",
            "scene",
            "characters",
            "emotion",
            "props"
    );
    private static final Map<String, Object> SCHEMA = createSchema();

    private GeminiStoryboardResponseSchema() {
    }

    public static Map<String, Object> schema() {
        return SCHEMA;
    }

    private static Map<String, Object> createSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of(
                                "type", "string",
                                "description", "A concise, natural Korean title, preferably 4-18 characters, "
                                        + "that hints at the central contrast without summarizing all events."
                        ),
                        "cast_continuity", Map.of(
                                "type", "string",
                                "description", "In English, identify recurring people, source-supported names "
                                        + "and relationships, group membership or count, and only explicitly "
                                        + "annotated persistent traits. Exclude plot events, temporary actions "
                                        + "or emotions, inferred appearance, and internal labels."
                        ),
                        "panels", createPanelsSchema()
                ),
                "propertyOrdering", ROOT_PROPERTY_ORDERING,
                "required", ROOT_PROPERTY_ORDERING
        );
    }

    private static Map<String, Object> createPanelsSchema() {
        return Map.of(
                "type", "array",
                "minItems", 4,
                "maxItems", 4,
                "items", Map.of(
                        "type", "object",
                        "properties", createPanelPropertiesSchema(),
                        "propertyOrdering", PANEL_PROPERTY_ORDERING,
                        "required", PANEL_PROPERTY_ORDERING
                )
        );
    }

    private static Map<String, Object> createPanelPropertiesSchema() {
        return Map.of(
                "panel_number", Map.of(
                        "type", "integer",
                        "minimum", 1,
                        "maximum", 4,
                        "description", "The one-based panel number in reading order."
                ),
                "story_role", Map.of(
                        "type", "string",
                        "enum", List.of("setup", "action", "escalation", "resolution"),
                        "description", "The panel's required narrative role."
                ),
                "caption", Map.of(
                        "type", "string",
                        "description", "One natural Korean caption rendered verbatim in the panel: one spoken "
                                + "beat and one primary text block, preferably 2-18 Unicode characters including "
                                + "spaces and never more than 24."
                ),
                "scene", Map.of(
                        "type", "string",
                        "description", "One concrete English sentence describing the setting, visible action, "
                                + "and composition. Exclude hidden thoughts, caption repetition, persistent "
                                + "traits, and internal labels."
                ),
                "characters", Map.of(
                        "type", "string",
                        "description", "In English, describe visible people, positions, actions, and interactions, "
                                + "including relevant temporary annotated poses. Do not repeat the setting or "
                                + "persistent traits."
                ),
                "emotion", Map.of(
                        "type", "string",
                        "description", "In English, describe only temporary visible facial and postural emotion."
                ),
                "props", Map.of(
                        "type", "array",
                        "maxItems", 3,
                        "items", Map.of("type", "string"),
                        "description", "Zero to three objects that must visibly appear in the panel; exclude "
                                + "implied or invisible objects."
                )
        );
    }
}
