package com.harudle.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.ProfileImageStorageException;
import com.harudle.auth.infrastructure.S3ProfileImageStorage;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.profile.configuration.ProfileImageProperties;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class ProfileImageUrlResolverTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
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
    @ValueSource(ints = {-1, 0, 11})
    @DisplayName("허용 범위 밖의 이미지 코드로 URL을 만들지 않는다")
    void rejectsInvalidImageCode(int imageCode) {
        assertThatThrownBy(() -> resolver("https://www.harudle.com/profile-images/v1").resolve(imageCode))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("개인 사진이 있으면 기본 코드보다 우선하고 서명 주소와 만료 시각을 반환한다")
    void prefersUploadedImage() {
        S3ProfileImageStorage storage = mock(S3ProfileImageStorage.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("storage", storage);
        URI url = URI.create("https://s3.example/profile.webp?signature=example");
        Instant expiresAt = Instant.parse("2026-10-21T00:15:00Z");
        when(storage.createAccessUrl("profile-key")).thenReturn(new ImageAccessUrl(url, expiresAt));
        ProfileImageUrlResolver resolver = new ProfileImageUrlResolver(
                new ProfileImageProperties(URI.create("https://www.harudle.com/profile-images/v1")),
                beans.getBeanProvider(S3ProfileImageStorage.class)
        );

        ProfileImageUrlResolver.ResolvedImage image = resolver.resolve((short) 3, "profile-key");

        assertThat(image.url()).isEqualTo(url);
        assertThat(image.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("개인 사진 저장소가 없어도 기본 이미지는 만료 시각 없이 조회한다")
    void resolvesDefaultWithoutStorage() {
        ProfileImageUrlResolver.ResolvedImage image = resolver("https://example.com/profiles").resolve((short) 3, null);

        assertThat(image.url()).isEqualTo(URI.create("https://example.com/profiles/3.png"));
        assertThat(image.expiresAt()).isNull();
    }

    @Test
    @DisplayName("미배정 회원은 이미지 주소와 만료 시각이 없다")
    void resolvesUnassignedProfile() {
        ProfileImageUrlResolver.ResolvedImage image = resolver("https://example.com/profiles").resolve(null, null);

        assertThat(image.url()).isNull();
        assertThat(image.expiresAt()).isNull();
    }

    @Test
    @DisplayName("개인 사진 저장소가 없으면 기본 이미지로 바꾸지 않고 조회 실패를 알린다")
    void rejectsUploadedImageWithoutStorage() {
        assertThatThrownBy(() -> resolver("https://example.com/profiles").resolve((short) 3, "profile-key"))
                .isInstanceOf(ProfileImageStorageException.class);
    }

    private ProfileImageUrlResolver resolver(String baseUrl) {
        return new ProfileImageUrlResolver(
                new ProfileImageProperties(URI.create(baseUrl)),
                new StaticListableBeanFactory().getBeanProvider(S3ProfileImageStorage.class)
        );
    }
}
