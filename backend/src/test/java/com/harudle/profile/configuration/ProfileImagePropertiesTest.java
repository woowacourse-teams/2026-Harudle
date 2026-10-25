package com.harudle.profile.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProfileImagePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProfileConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:5173/profile-images/v1",
            "https://www.harudle.com/profile-images/v1",
            "https://cdn.example.com/profile-images/v2/"
    })
    @DisplayName("환경별 프로필 이미지 기준 주소를 바인딩한다")
    void bindsImageBaseUrl(String imageBaseUrl) {
        contextRunner.withPropertyValues(
                "harudle.profile.image-base-url=" + imageBaseUrl
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProfileImageProperties.class).imageBaseUrl())
                    .isEqualTo(URI.create(imageBaseUrl));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/profile-images/v1",
            "ftp://example.com/profile-images/v1",
            "https:profile-images",
            "https:/profile-images",
            "https://user:password@example.com/profile-images/v1",
            "https://example.com/profile-images/v1?token=value",
            "https://example.com/profile-images/v1#image"
    })
    @DisplayName("잘못된 주소나 인증 정보와 쿼리가 포함된 프로필 이미지 주소로는 시작할 수 없다")
    void rejectsInvalidImageBaseUrl(String imageBaseUrl) {
        contextRunner.withPropertyValues(
                "harudle.profile.image-base-url=" + imageBaseUrl
        ).run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("프로필 이미지 기준 주소가 없으면 시작할 수 없다")
    void rejectsMissingImageBaseUrl() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }
}
