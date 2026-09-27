package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ImageUrlProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ImageUrlProviderConfiguration.class);

    @Test
    @DisplayName("URL 발급 어댑터가 없으면 명시적인 unavailable 구현을 제공한다")
    void provideUnavailableImageUrlProvider() {
        contextRunner.run(context -> {
            ImageUrlProvider provider = context.getBean(ImageUrlProvider.class);

            assertThatThrownBy(() -> provider.createAccessUrl("generated/comic.png"))
                    .isInstanceOf(ImageStorageException.class)
                    .hasMessage("이미지 URL 발급 어댑터가 구성되지 않았습니다.");
        });
    }

    @Test
    @DisplayName("실제 URL 발급 어댑터가 있으면 fallback 구현을 만들지 않는다")
    void preferConfiguredImageUrlProvider() {
        ImageUrlProvider configuredProvider = imageObjectKey -> new ImageAccessUrl(
                URI.create("https://images.harudle.example/comic.png"),
                Instant.parse("2026-08-06T12:00:00Z")
        );

        contextRunner
                .withBean(ImageUrlProvider.class, () -> configuredProvider)
                .run(context -> assertThat(context.getBean(ImageUrlProvider.class))
                        .isSameAs(configuredProvider));
    }

    @Test
    @DisplayName("외부 어댑터가 활성화되면 unavailable 구현으로 누락을 숨기지 않는다")
    void doNotProvideFallbackWhenAdaptersAreEnabled() {
        contextRunner
                .withPropertyValues("harudle.generation.adapters.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(ImageUrlProvider.class));
    }
}
