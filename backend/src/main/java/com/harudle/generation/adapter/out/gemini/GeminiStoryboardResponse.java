package com.harudle.generation.adapter.out.gemini;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GeminiStoryboardResponse(
        String title,
        String castContinuity,
        List<Panel> panels
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Panel(
            int panelNumber,
            String storyRole,
            String caption,
            String scene,
            String characters,
            String emotion,
            List<String> props
    ) {
    }
}
