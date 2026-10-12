package com.harudle.generation.adapter.out.storyboard;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

public final class StoryboardSchemaValidator {

    private StoryboardSchemaValidator() {
    }

    public static void validate(JsonNode node, Map<String, Object> schema) {
        validate(node, schema, "$", 0);
    }

    private static void validate(JsonNode node, Map<String, Object> schema, String path, int depth) {
        require(depth <= 30, path, "nesting is too deep");
        String type = (String) schema.get("type");
        boolean validType = switch (type) {
            case "object" -> node != null && node.isObject();
            case "array" -> node != null && node.isArray();
            case "string" -> node != null && node.isString();
            case "integer" -> node != null && node.isIntegralNumber();
            default -> false;
        };
        require(validType, path, "expected " + type);
        validateEnum(node, schema, path);
        switch (type) {
            case "object" -> validateObject(node, schema, path, depth);
            case "array" -> validateArray(node, schema, path, depth);
            case "integer" -> validateInteger(node, schema, path);
            default -> { }
        }
    }

    private static void validateEnum(JsonNode node, Map<String, Object> schema, String path) {
        if (schema.get("enum") instanceof List<?> values) {
            require(values.contains(node.asString()), path, "unexpected enum value");
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateObject(JsonNode node, Map<String, Object> schema, String path, int depth) {
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        required.forEach(key -> require(node.has(key), path + "." + key, "required field is missing"));
        node.properties().forEach(entry -> {
            String key = entry.getKey();
            require(properties.containsKey(key), path + "." + key, "unknown field");
            validate(entry.getValue(), (Map<String, Object>) properties.get(key), path + "." + key, depth + 1);
        });
    }

    @SuppressWarnings("unchecked")
    private static void validateArray(JsonNode node, Map<String, Object> schema, String path, int depth) {
        require(node.size() >= ((Number) schema.getOrDefault("minItems", 0)).intValue(), path, "too few items");
        require(node.size() <= ((Number) schema.getOrDefault("maxItems", Integer.MAX_VALUE)).intValue(),
                path, "too many items");
        Map<String, Object> items = (Map<String, Object>) schema.get("items");
        for (int index = 0; index < node.size(); index++) {
            validate(node.get(index), items, path + "[" + index + "]", depth + 1);
        }
    }

    private static void validateInteger(JsonNode node, Map<String, Object> schema, String path) {
        long value = node.asLong();
        require(value >= ((Number) schema.getOrDefault("minimum", Long.MIN_VALUE)).longValue(),
                path, "below minimum");
        require(value <= ((Number) schema.getOrDefault("maximum", Long.MAX_VALUE)).longValue(),
                path, "above maximum");
    }

    private static void require(boolean condition, String path, String reason) {
        if (!condition) {
            throw new IllegalArgumentException("Storyboard schema violation: " + path + ": " + reason);
        }
    }
}
