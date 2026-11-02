package com.harudle.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.ProfileImageStorageException;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

class S3ProfileImageStorageTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String OBJECT_KEY = "harudle/profile-images/dev/" + USER_ID
            + "/22222222-2222-2222-2222-222222222222.webp";
    private static final byte[] WEBP_BYTES = Base64.getDecoder().decode(
            "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA"
    );

    private final S3Client s3Client = mock(S3Client.class);
    private final S3Presigner s3Presigner = mock(S3Presigner.class);
    private final S3ProfileImageStorage storage = storage("dev", Duration.ofMinutes(15));

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("현재 환경과 회원의 새 경로에 WebP 내용과 형식을 지정하고 덮어쓰기 없이 저장한다")
    void storesWebpInCurrentEnvironment(String environment) throws IOException {
        S3ProfileImageStorage configuredStorage = storage(environment, Duration.ofMinutes(15));

        String key = configuredStorage.store(USER_ID, WEBP_BYTES);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());
        PutObjectRequest request = requestCaptor.getValue();
        assertThat(key).startsWith("harudle/profile-images/" + environment + "/" + USER_ID + "/")
                .endsWith(".webp");
        String filename = key.substring(key.lastIndexOf('/') + 1, key.length() - ".webp".length());
        assertThat(UUID.fromString(filename)).isNotNull();
        assertThat(request.bucket()).isEqualTo("test-bucket");
        assertThat(request.key()).isEqualTo(key);
        assertThat(request.contentType()).isEqualTo("image/webp");
        assertThat(request.contentLength()).isEqualTo((long) WEBP_BYTES.length);
        assertThat(request.ifNoneMatch()).isEqualTo("*");
        assertThat(request.acl()).isNull();
        try (InputStream stream = bodyCaptor.getValue().contentStreamProvider().newStream()) {
            assertThat(stream.readAllBytes()).containsExactly(WEBP_BYTES);
        }
        verifyNoInteractions(s3Presigner);
    }

    @Test
    @DisplayName("같은 회원이 다시 저장해도 이전 파일 경로를 재사용하지 않는다")
    void usesNewKeyForEachUpload() {
        String first = storage.store(USER_ID, WEBP_BYTES);
        String second = storage.store(USER_ID, WEBP_BYTES);

        assertThat(first).isNotEqualTo(second);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("파일 내용이 없으면 S3에 저장하지 않는다")
    void rejectsEmptyWebp(byte[] bytes) {
        assertThatThrownBy(() -> storage.store(USER_ID, bytes)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3Client, s3Presigner);
    }

    @Test
    @DisplayName("WebP가 아닌 내용을 WebP 형식으로 저장하지 않는다")
    void rejectsNonWebp() {
        byte[] bytes = "this is not a WebP image".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> storage.store(USER_ID, bytes)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3Client, s3Presigner);
    }

    @Test
    @DisplayName("저장소 크기 제한을 넘으면 S3 요청을 보내지 않는다")
    void rejectsOversizedWebp() {
        S3StorageProperties properties = properties("dev", Duration.ofMinutes(15), DataSize.ofBytes(12));
        S3ProfileImageStorage limitedStorage = new S3ProfileImageStorage(s3Client, s3Presigner, properties);

        assertThatThrownBy(() -> limitedStorage.store(USER_ID, WEBP_BYTES))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3Client, s3Presigner);
    }

    @Test
    @DisplayName("지정한 프로필 파일 하나만 삭제한다")
    void deletesOnlyRequestedProfileImage() {
        storage.delete(OBJECT_KEY);

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(captor.getValue().key()).isEqualTo(OBJECT_KEY);
        assertThat(captor.getValue().overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofSeconds(2));
        verifyNoInteractions(s3Presigner);
    }

    @ParameterizedTest
    @MethodSource("invalidObjectKeys")
    @DisplayName("다른 환경과 일기 이미지, 기본 이미지 및 잘못된 경로는 삭제하거나 URL을 발급하지 않는다")
    void rejectsInvalidObjectKey(String key) {
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.createAccessUrl(key)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3Client, s3Presigner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("설정한 유효 시간으로 실제 서명된 GET 주소와 만료 시각을 발급한다")
    void createsPresignedUrlWithoutNetworkCall(String environment) {
        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-key", "test-secret")
                ))
                .build()) {
            Duration ttl = Duration.ofMinutes(5);
            S3ProfileImageStorage configuredStorage = new S3ProfileImageStorage(
                    s3Client, presigner, properties(environment, ttl, DataSize.ofMegabytes(20))
            );
            String key = OBJECT_KEY.replace("/dev/", "/" + environment + "/");
            Instant before = Instant.now();

            ImageAccessUrl result = configuredStorage.createAccessUrl(key);

            assertThat(result.url().getScheme()).isEqualTo("https");
            assertThat(result.url().getHost()).isEqualTo("test-bucket.s3.ap-northeast-2.amazonaws.com");
            assertThat(result.url().getPath()).isEqualTo("/" + key);
            assertThat(result.url().getQuery()).contains("X-Amz-Expires=300", "X-Amz-Signature=");
            assertThat(result.expiresAt()).isBetween(before.plus(ttl), Instant.now().plus(ttl));
            verifyNoInteractions(s3Client);
        }
    }

    @Test
    @DisplayName("업로드 실패 시 결과 경로를 반환하지 않고 정리 대상 경로와 원인을 예외에 보존한다")
    void preservesUploadFailureAndKey() {
        var failure = S3Exception.builder().statusCode(503).message("upload failed").build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(failure);

        assertThatThrownBy(() -> storage.store(USER_ID, WEBP_BYTES))
                .isInstanceOfSatisfying(ProfileImageStorageException.class, exception -> {
                    assertThat(exception.getCause()).isSameAs(failure);
                    assertThat(exception.getObjectKey()).startsWith("harudle/profile-images/dev/" + USER_ID + "/");
                });
    }

    @Test
    @DisplayName("삭제 실패 시 재처리할 파일 경로와 원인을 보존한다")
    void preservesDeleteFailureAndKey() {
        SdkClientException failure = SdkClientException.create("delete failed");
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> storage.delete(OBJECT_KEY))
                .isInstanceOfSatisfying(ProfileImageStorageException.class, exception -> {
                    assertThat(exception.getCause()).isSameAs(failure);
                    assertThat(exception.getObjectKey()).isEqualTo(OBJECT_KEY);
                });
    }

    @Test
    @DisplayName("조회 주소 발급 실패를 파일 경로와 함께 전달한다")
    void preservesPresignFailure() {
        SdkClientException failure = SdkClientException.create("presign failed");
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> storage.createAccessUrl(OBJECT_KEY))
                .isInstanceOfSatisfying(ProfileImageStorageException.class, exception -> {
                    assertThat(exception.getCause()).isSameAs(failure);
                    assertThat(exception.getObjectKey()).isEqualTo(OBJECT_KEY);
                });
    }

    @Test
    @DisplayName("잘못된 환경으로 프로필 저장소를 구성할 수 없다")
    void rejectsInvalidEnvironment() {
        assertThatThrownBy(() -> storage("staging", Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<String> invalidObjectKeys() {
        return Stream.of(
                null,
                "",
                OBJECT_KEY.replace("/dev/", "/prod/"),
                OBJECT_KEY.replace("profile-images", "generated/diary-images"),
                "https://example.com/" + OBJECT_KEY,
                "/profile-images/v1/1.png",
                OBJECT_KEY.replace(USER_ID.toString(), ".."),
                OBJECT_KEY.replace(USER_ID.toString(), "1-1-1-1-1"),
                OBJECT_KEY.replace(".webp", ".png"),
                OBJECT_KEY + "?download=true",
                OBJECT_KEY.replace("/dev/", "/dev//"),
                OBJECT_KEY.replace("/dev/", "/dev-other/")
        );
    }

    private S3ProfileImageStorage storage(String environment, Duration ttl) {
        return new S3ProfileImageStorage(s3Client, s3Presigner, properties(environment, ttl, DataSize.ofMegabytes(20)));
    }

    private S3StorageProperties properties(String environment, Duration ttl, DataSize maxObjectSize) {
        return new S3StorageProperties(
                "test-bucket", "ap-northeast-2", environment,
                "harudle/generated/diary-images/" + environment,
                "harudle/references/generation/" + environment,
                maxObjectSize, ttl
        );
    }
}
