package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageProviderPropertiesTest {
    @Test
    void disabledFallbackNeedsNoApiKeyAndUsesFlareDefaults() {
        var properties = new ImageProviderProperties(null, null, null, null, null);
        assertThat(properties.openaiEnabled()).isFalse();
        assertThat(properties.openaiModel()).isEqualTo("gpt-image-2.5-flare");
        assertThat(properties.openaiTimeout()).isEqualTo(Duration.ofSeconds(180));
        assertThat(properties.openaiQuality()).isEqualTo("medium");
        assertValid(properties);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "key with spaces", "key\r\nHeader:injected"})
    void enabledFallbackRejectsInvalidApiKey(String key) {
        assertInvalid(new ImageProviderProperties("openai", key, null, null, null));
    }

    @Test
    void validatesModelQualityAndTimeoutAndMasksKey() {
        var valid = new ImageProviderProperties("openai", "test-secret", null, null, null);
        assertValid(valid);
        assertThat(valid.toString()).doesNotContain("test-secret");
        assertInvalid(new ImageProviderProperties("openai", null, null, null, null));
        assertInvalid(new ImageProviderProperties("openai", "key", "unknown-model", null, null));
        assertInvalid(new ImageProviderProperties("openai", "key", null, null, "unknown"));
        assertInvalid(new ImageProviderProperties("openai", "key", null, Duration.ZERO, null));
        assertInvalid(new ImageProviderProperties("openai", "key", null, Duration.ofSeconds(301), null));
        assertInvalid(new ImageProviderProperties("codex", null, null, null, null));
    }

    private void assertValid(ImageProviderProperties properties) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }

    private void assertInvalid(ImageProviderProperties properties) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();
        }
    }
}
