package com.harudle.generation.config;

import com.harudle.generation.domain.GenerationPrompt;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("harudle.generation.prompt-bootstrap")
public record GenerationPromptBootstrapProperties(
        boolean enabled,
        String storyboardPromptText,
        String imageStylePromptText,
        String imageAssetObjectKey
) {

    public GenerationPrompt createPrompt() {
        if (!enabled) {
            throw new IllegalStateException("생성 프롬프트 초기화가 활성화되지 않았습니다.");
        }

        return new GenerationPrompt(
                storyboardPromptText,
                imageStylePromptText,
                imageAssetObjectKey
        );
    }
}
