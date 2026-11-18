package com.harudle.feed.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FeedUrlPropertiesTest {

    private static final UUID FEED_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FeedConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"https://harudle.example/feeds", "https://harudle.example/feeds/"})
    void bindsFrontendBaseAndBuildsStablePublicFeedUrl(String baseUrl) {
        contextRunner.withPropertyValues("harudle.feed.public-base-url=" + baseUrl).run(context -> {
            assertThat(context).hasNotFailed();
            FeedUrlProperties properties = context.getBean(FeedUrlProperties.class);
            assertThat(properties.publicBaseUrl()).isEqualTo(URI.create(baseUrl));
            assertThat(properties.shareUrl(FEED_ID))
                    .isEqualTo(URI.create("https://harudle.example/feeds/" + FEED_ID));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/feeds", "ftp://harudle.example/feeds", "https:feeds", "https:/feeds",
            "https://harudle.example/feeds?category=1", "https://harudle.example/feeds#detail"
    })
    void rejectsBaseUrlsThatCannotRepresentPublicFeedPaths(String baseUrl) {
        contextRunner.withPropertyValues("harudle.feed.public-base-url=" + baseUrl)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void requiresExplicitBaseUrlProperty() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }
}
