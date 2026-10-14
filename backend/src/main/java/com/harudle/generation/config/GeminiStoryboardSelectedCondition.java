package com.harudle.generation.config;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

final class GeminiStoryboardSelectedCondition extends AnyNestedCondition {

    GeminiStoryboardSelectedCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(prefix = "harudle.generation.storyboard", name = "provider",
            havingValue = "gemini", matchIfMissing = true)
    static class Primary {
    }

    @ConditionalOnProperty(prefix = "harudle.generation.storyboard", name = "fallback-provider", havingValue = "gemini")
    static class Fallback {
    }
}
