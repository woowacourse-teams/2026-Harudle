package com.harudle.generation.adapter.out.storyboard;

import com.harudle.generation.diary.domain.Storyboard;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class StoryboardJsonCodec {

    private final ObjectMapper objectMapper;
    private final StoryboardResponseMapper responseMapper;

    public StoryboardJsonCodec(ObjectMapper objectMapper, StoryboardResponseMapper responseMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.responseMapper = Objects.requireNonNull(responseMapper);
    }

    public Storyboard decode(String content) {
        JsonNode json = objectMapper.readTree(content);
        StoryboardSchemaValidator.validate(json, StoryboardResponseSchema.schema());
        return responseMapper.map(objectMapper.treeToValue(json, StoryboardResponse.class));
    }

    public String schemaJson() {
        return objectMapper.writeValueAsString(StoryboardResponseSchema.schema());
    }
}
