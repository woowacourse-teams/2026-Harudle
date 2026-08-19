package com.harudle.guest.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GuestSessionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    @DisplayName("게스트 세션 유효 기간 설정을 바인딩한다")
    void bindsGuestSessionTtl() {
        contextRunner.withPropertyValues(
                "harudle.guest.session.ttl=30d"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            GuestSessionProperties properties = context.getBean(GuestSessionProperties.class);
            assertThat(properties.ttl()).isEqualTo(Duration.ofDays(30));
        });
    }

    @Test
    @DisplayName("게스트 세션 유효 기간이 없으면 설정 바인딩에 실패한다")
    void rejectsMissingGuestSessionTtl() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("게스트 세션 유효 기간이 양수가 아니면 설정 바인딩에 실패한다")
    void rejectsNonPositiveGuestSessionTtl() {
        contextRunner.withPropertyValues(
                "harudle.guest.session.ttl=0s"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GuestSessionProperties.class)
    static class TestConfiguration {
    }
}
