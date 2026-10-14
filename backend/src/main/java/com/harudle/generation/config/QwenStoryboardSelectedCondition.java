package com.harudle.generation.config;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

final class QwenStoryboardSelectedCondition extends AnyNestedCondition {

    QwenStoryboardSelectedCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(prefix = "harudle.generation.storyboard", name = "provider", havingValue = "qwen")
    static class Primary {
    }

    @ConditionalOnProperty(prefix = "harudle.generation.storyboard", name = "fallback-provider", havingValue = "qwen")
    static class Fallback {
    }
}
