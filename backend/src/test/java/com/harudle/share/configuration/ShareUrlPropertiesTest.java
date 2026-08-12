package com.harudle.share.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ShareUrlPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    @DisplayName("공유 URL 기준 주소 설정을 바인딩한다")
    void bindPublicBaseUrl() {
        contextRunner.withPropertyValues(
                "harudle.share.public-base-url=https://harudle.example/shares"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            ShareUrlProperties properties = context.getBean(ShareUrlProperties.class);
            assertThat(properties.publicBaseUrl())
                    .isEqualTo(URI.create("https://harudle.example/shares"));
        });
    }

    @Test
    @DisplayName("상대 경로는 공유 URL 기준 주소로 사용할 수 없다")
    void rejectRelativePublicBaseUrl() {
        contextRunner.withPropertyValues(
                "harudle.share.public-base-url=/shares"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("HTTP 또는 HTTPS가 아닌 주소는 공유 URL 기준 주소로 사용할 수 없다")
    void rejectUnsupportedScheme() {
        contextRunner.withPropertyValues(
                "harudle.share.public-base-url=ftp://harudle.example/shares"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ShareUrlProperties.class)
    static class TestConfiguration {
    }
}
