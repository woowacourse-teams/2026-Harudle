package com.harudle.feed.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FeedPreviewUrlsTest {

    private static final UUID FEED = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(FeedConfiguration.class)
            .withPropertyValues("harudle.feed.public-base-url=https://harudle.example/feeds/");

    @Test
    void defaultPublicUrlsNeedNoNewEnvironmentAndKeepTheFrontendDestination() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            FeedPreviewUrls urls = application.getBean(FeedPreviewUrls.class);
            assertThat(urls.shareUrl(FEED)).isEqualTo(URI.create("https://harudle.example/feeds/" + FEED));
            assertThat(urls.imageUrl(FEED)).isEqualTo(URI.create("https://harudle.example/api/v1/public/feeds/" + FEED + "/image"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://images.example/api/v1/public/feeds", "https://images.example/api/v1/public/feeds/"})
    void optionalCdnChangesOnlyTheImageUrl(String base) {
        context.withPropertyValues("harudle.feed.preview.image-base-url=" + base).run(application -> {
            assertThat(application).hasNotFailed();
            FeedPreviewUrls urls = application.getBean(FeedPreviewUrls.class);
            assertThat(urls.imageUrl(FEED)).isEqualTo(URI.create("https://images.example/api/v1/public/feeds/" + FEED + "/image"));
            assertThat(urls.shareUrl(FEED).getHost()).isEqualTo("harudle.example");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"/images", "javascript:alert(1)", "ftp://images.example", "https://images.example?q=1",
            "https://images.example#fragment", "https://user:password@images.example", "not a uri"})
    void rejectsInvalidCdnConfigurationBeforeServingPublicHtml(String base) {
        context.withPropertyValues("harudle.feed.preview.image-base-url=" + base)
                .run(application -> assertThat(application).hasFailed());
    }
}
