package com.harudle.generation.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("harudle.generation.storyboard")
public record StoryboardProviderProperties(
        @DefaultValue("gemini") @NotBlank @Pattern(regexp = "gemini|qwen") String provider
) {
}
