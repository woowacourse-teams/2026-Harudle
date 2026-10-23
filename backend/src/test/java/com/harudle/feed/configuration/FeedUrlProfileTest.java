package com.harudle.feed.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

class FeedUrlProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FeedConfiguration.class)
            .withPropertyValues("spring.config.location=classpath:/application.yml", "spring.config.import=")
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                context.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                new ConfigDataApplicationContextInitializer().initialize(context);
            });

    @Test
    void keepsLocalDefaultWithoutProductionProfile() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FeedUrlProperties.class).publicBaseUrl())
                    .isEqualTo(URI.create("http://localhost:5173/feeds"));
        });
    }

    @Test
    void startsProductionWithExplicitFeedBaseUrl() {
        contextRunner.withPropertyValues(
                "spring.profiles.active=prod",
                "FEED_PUBLIC_BASE_URL=https://www.harudle.com/feeds"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FeedUrlProperties.class).publicBaseUrl())
                    .isEqualTo(URI.create("https://www.harudle.com/feeds"));
        });
    }

    @Test
    void rejectsProductionWithoutFeedBaseUrl() {
        contextRunner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsProductionWhenOnlyLegacyShareBaseUrlIsConfigured() {
        contextRunner.withPropertyValues(
                "spring.profiles.active=prod",
                "SHARE_PUBLIC_BASE_URL=https://www.harudle.com/shares"
        ).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void rejectsProductionWithBlankFeedBaseUrl(String baseUrl) {
        contextRunner.withPropertyValues("spring.profiles.active=prod", "FEED_PUBLIC_BASE_URL=" + baseUrl)
                .run(context -> assertThat(context).hasFailed());
    }
}
