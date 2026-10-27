package com.harudle.generation.adapter.out.gemini;

import com.harudle.generation.adapter.out.storyboard.StoryboardResponseSchema;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GeminiStoryboardResponseSchema {

    private static final Map<String, Object> SCHEMA = addPropertyOrdering(StoryboardResponseSchema.schema());

    private GeminiStoryboardResponseSchema() {
    }

    public static Map<String, Object> schema() {
        return SCHEMA;
    }

    private static Object convert(Object value) {
        if (value instanceof Map<?, ?> map) {
            return addPropertyOrdering(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(GeminiStoryboardResponseSchema::convert).toList();
        }
        return value;
    }

    private static Map<String, Object> addPropertyOrdering(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> copyEntry(result, key.toString(), value));
        return Collections.unmodifiableMap(result);
    }

    private static void copyEntry(Map<String, Object> result, String key, Object value) {
        if ("additionalProperties".equals(key)) {
            return;
        }
        result.put(key, convert(value));
        if ("properties".equals(key) && value instanceof Map<?, ?> fields) {
            result.put("propertyOrdering", fields.keySet().stream().map(Object::toString).toList());
        }
    }
}
