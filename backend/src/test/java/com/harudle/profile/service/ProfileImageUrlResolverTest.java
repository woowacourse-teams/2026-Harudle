package com.harudle.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.profile.configuration.ProfileImageProperties;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileImageUrlResolverTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("저장된 이미지 코드에 대응하는 고정 PNG 주소를 반환한다")
    void resolvesAssignedImageCode(int imageCode) {
        ProfileImageUrlResolver resolver = resolver("https://www.harudle.com/profile-images/v1");

        assertThat(resolver.resolve(imageCode))
                .isEqualTo(URI.create("https://www.harudle.com/profile-images/v1/" + imageCode + ".png"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:5173/profile-images/v1",
            "https://cdn.example.com/profile-images/v2/"
    })
    @DisplayName("기준 주소의 마지막 슬래시 유무와 관계없이 경로를 연결한다")
    void resolvesDifferentBaseUrls(String baseUrl) {
        String expectedBaseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";

        assertThat(resolver(baseUrl).resolve(3))
                .isEqualTo(URI.create(expectedBaseUrl + "3.png"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 6})
    @DisplayName("허용 범위 밖의 이미지 코드로 URL을 만들지 않는다")
    void rejectsInvalidImageCode(int imageCode) {
        assertThatThrownBy(() -> resolver("https://www.harudle.com/profile-images/v1").resolve(imageCode))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ProfileImageUrlResolver resolver(String baseUrl) {
        return new ProfileImageUrlResolver(new ProfileImageProperties(URI.create(baseUrl)));
    }
}
