package com.harudle.guest.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GuestSessionCookiePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    @DisplayName("게스트 세션 Cookie 설정을 바인딩한다")
    void bindsGuestSessionCookieProperties() {
        contextRunner.withPropertyValues(
                "harudle.guest.cookie.name=guest_session",
                "harudle.guest.cookie.path=/api/v1/guest",
                "harudle.guest.cookie.secure=true",
                "harudle.guest.cookie.same-site=Lax"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            GuestSessionCookieProperties properties = context.getBean(
                    GuestSessionCookieProperties.class
            );
            assertThat(properties.name()).isEqualTo("guest_session");
            assertThat(properties.path()).isEqualTo("/api/v1/guest");
            assertThat(properties.secure()).isTrue();
            assertThat(properties.sameSite()).isEqualTo("Lax");
        });
    }

    @Test
    @DisplayName("게스트 세션 Cookie 이름이 없으면 설정 바인딩에 실패한다")
    void rejectsMissingCookieName() {
        contextRunner.withPropertyValues(
                "harudle.guest.cookie.path=/api/v1/guest",
                "harudle.guest.cookie.secure=true",
                "harudle.guest.cookie.same-site=Lax"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("게스트 세션 Cookie 경로가 절대 경로가 아니면 설정 바인딩에 실패한다")
    void rejectsRelativeCookiePath() {
        contextRunner.withPropertyValues(
                "harudle.guest.cookie.name=guest_session",
                "harudle.guest.cookie.path=api/v1/guest",
                "harudle.guest.cookie.secure=true",
                "harudle.guest.cookie.same-site=Lax"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("지원하지 않는 SameSite 값이면 설정 바인딩에 실패한다")
    void rejectsUnsupportedSameSite() {
        contextRunner.withPropertyValues(
                "harudle.guest.cookie.name=guest_session",
                "harudle.guest.cookie.path=/api/v1/guest",
                "harudle.guest.cookie.secure=true",
                "harudle.guest.cookie.same-site=Unknown"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("SameSite None Cookie가 Secure하지 않으면 설정 바인딩에 실패한다")
    void rejectsInsecureSameSiteNoneCookie() {
        contextRunner.withPropertyValues(
                "harudle.guest.cookie.name=guest_session",
                "harudle.guest.cookie.path=/api/v1/guest",
                "harudle.guest.cookie.secure=false",
                "harudle.guest.cookie.same-site=None"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GuestSessionCookieProperties.class)
    static class TestConfiguration {
    }
}
