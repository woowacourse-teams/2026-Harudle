package com.harudle.generation.adapter.out.storyboard;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StoryboardResponseSchema {

    private static final List<String> ROOT_FIELDS = List.of(
            "title",
            "cast_continuity",
            "panels",
            "visual_plan"
    );
    private static final List<String> VISUAL_PLAN_FIELDS = List.of(
            "environment_rule",
            "focal_colors"
    );
    private static final List<String> COLOR_TARGET_FIELDS = List.of(
            "prop",
            "component",
            "color_hex",
            "panel_numbers"
    );
    private static final List<String> PANEL_FIELDS = List.of(
            "panel_number",
            "story_role",
            "caption",
            "scene",
            "characters",
            "emotion",
            "props"
    );
    private static final Map<String, Object> SCHEMA = canonicalize(createSchema());

    private StoryboardResponseSchema() {
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
                        "panels", createPanelsSchema(),
                        "visual_plan", createVisualPlanSchema()
                ),

                "required", ROOT_FIELDS
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

                        "required", PANEL_FIELDS
                )
        );
    }

    private static Map<String, Object> createVisualPlanSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "environment_rule", Map.of(
                                "type", "string",
                                "description", "One or two English sentences preserving only the time, place, "
                                        + "and movement already supported by the four panels."
                        ),
                        "focal_colors", Map.of(
                                "type", "array",
                                "items", createColorTargetSchema(),
                                "description", "Color only meaningful existing props. Use an empty array when "
                                        + "no prop has a justified color target; never invent a prop to add color."
                        )
                ),

                "required", VISUAL_PLAN_FIELDS
        );
    }

    private static Map<String, Object> createColorTargetSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "prop", Map.of(
                                "type", "string",
                                "description", "Exact name of an existing props entry in every listed panel."
                        ),
                        "component", Map.of(
                                "type", "string",
                                "description", "One small, clearly bounded visible surface of that prop to fill."
                        ),
                        "color_hex", Map.of(
                                "type", "string",
                                "description", "One fitting opaque solid color in #RRGGBB format."
                        ),
                        "panel_numbers", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "maxItems", 4,
                                "items", Map.of("type", "integer", "minimum", 1, "maximum", 4),
                                "description", "All panel numbers containing this prop, in ascending order. "
                                        + "Use the same color for every appearance."
                        )
                ),

                "required", COLOR_TARGET_FIELDS
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

    private static Object canonicalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return canonicalize(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(StoryboardResponseSchema::canonicalizeValue).toList();
        }
        return value;
    }

    private static Map<String, Object> canonicalize(Map<?, ?> source) {
        Map<String, Object> target = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> copyEntry(target, source, entry.getKey().toString(), entry.getValue()));
        if ("object".equals(source.get("type"))) {
            target.put("additionalProperties", false);
        }
        return Collections.unmodifiableMap(target);
    }

    private static void copyEntry(Map<String, Object> target, Map<?, ?> source, String key, Object value) {
        if ("properties".equals(key) && value instanceof Map<?, ?> properties
                && source.get("required") instanceof List<?> fields) {
            Map<String, Object> ordered = new LinkedHashMap<>();
            fields.forEach(name -> ordered.put(name.toString(), canonicalizeValue(properties.get(name))));
            properties.keySet().stream().map(Object::toString).filter(name -> !ordered.containsKey(name)).sorted()
                    .forEach(name -> ordered.put(name, canonicalizeValue(properties.get(name))));
            target.put(key, Collections.unmodifiableMap(ordered));
            return;
        }
        target.put(key, canonicalizeValue(value));
    }
}
