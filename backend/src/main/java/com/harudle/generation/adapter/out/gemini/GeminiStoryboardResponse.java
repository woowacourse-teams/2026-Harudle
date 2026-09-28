package com.harudle.generation.adapter.out.gemini;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record GeminiStoryboardResponse(
        String title,
        String castContinuity,
        List<Panel> panels,
        VisualPlan visualPlan
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VisualPlan(String environmentRule, List<ColorTarget> focalColors) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ColorTarget(String prop, String component, String colorHex, List<Integer> panelNumbers) {
    }

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
