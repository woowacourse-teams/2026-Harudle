package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.generation.diary.service.port.ImageStorageException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@ExtendWith(MockitoExtension.class)
class S3ImageUrlProviderTest {

    private static final String OBJECT_KEY = "harudle/generated/diary-images/dev/diary-id/image.png";
    private static final Duration ACCESS_URL_TTL = Duration.ofMinutes(15);

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private PresignedGetObjectRequest presignedRequest;

    @Mock
    private ExternalApiLogger externalApiLogger;

    @Mock
    private AwsCredentialsProvider credentialsProvider;

    private S3ImageUrlProvider imageUrlProvider;
    private S3StorageProperties properties;

    @BeforeEach
    void setUp() {
        properties = new S3StorageProperties(
                "test-bucket",
                "ap-northeast-2",
                "dev",
                "harudle/generated/diary-images/dev",
                "harudle/references/generation/dev",
                DataSize.ofMegabytes(20),
                ACCESS_URL_TTL
        );
        imageUrlProvider = new S3ImageUrlProvider(
                s3Presigner,
                properties,
                new S3FailureReporter(new S3ExceptionTranslator(), externalApiLogger),
                credentialsProvider
        );
    }

    @Test
    @DisplayName("S3 Object Key로 설정된 유효 시간의 presigned GET URL을 발급한다")
    void createPresignedGetObjectUrl() throws Exception {
        URI url = URI.create("https://test-bucket.s3.ap-northeast-2.amazonaws.com/image.png?signature=test");
        Instant expiresAt = Instant.parse("2026-08-12T03:15:00Z");
        when(presignedRequest.url()).thenReturn(url.toURL());
        when(presignedRequest.expiration()).thenReturn(expiresAt);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenReturn(presignedRequest);

        ImageAccessUrl accessUrl = imageUrlProvider.createAccessUrl(OBJECT_KEY);

        ArgumentCaptor<GetObjectPresignRequest> requestCaptor =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner).presignGetObject(requestCaptor.capture());
        GetObjectPresignRequest request = requestCaptor.getValue();
        assertThat(request.signatureDuration()).isEqualTo(ACCESS_URL_TTL);
        assertThat(request.getObjectRequest().bucket()).isEqualTo("test-bucket");
        assertThat(request.getObjectRequest().key()).isEqualTo(OBJECT_KEY);
        assertThat(request.getObjectRequest().overrideConfiguration()).isEmpty();
        verifyNoInteractions(credentialsProvider);
        assertThat(accessUrl.url()).isEqualTo(url);
        assertThat(accessUrl.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("빈 Object Key는 S3 Presigner에 전달하지 않는다")
    void rejectBlankObjectKey() {
        assertThatThrownBy(() -> imageUrlProvider.createAccessUrl(" "))
                .isInstanceOf(ImageStorageException.class)
                .hasRootCauseMessage("이미지 Object Key가 필요합니다.");
        verify(externalApiLogger).warn(
                eq(new ExternalApiFailure(
                        "s3",
                        "presign_get_object",
                        "REQUEST_VALIDATION_ERROR",
                        null,
                        null,
                        null
                )),
                any(IllegalArgumentException.class)
        );
        verifyNoInteractions(s3Presigner);
    }

    @Test
    @DisplayName("S3 presign 오류를 이미지 저장소 예외로 변환한다")
    void translatePresignException() {
        SdkClientException cause = SdkClientException.builder()
                .message("credentials unavailable")
                .build();
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenThrow(cause);

        assertThatThrownBy(() -> imageUrlProvider.createAccessUrl(OBJECT_KEY))
                .isInstanceOf(ImageStorageException.class)
                .hasMessageContaining("S3 이미지 접근 URL 발급")
                .hasMessageNotContaining(OBJECT_KEY)
                .hasCause(cause);
        verify(externalApiLogger).error(
                eq(new ExternalApiFailure(
                        "s3",
                        "presign_get_object",
                        "CONFIGURATION_ERROR",
                        null,
                        null,
                        null
                )),
                eq(cause)
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {-300, 300})
    @DisplayName("자격 만료와 URL 만료 중 이른 시각을 반환하며 같은 자격 스냅샷으로 서명한다")
    void useEarlierExpirationFromTheSigningCredentials(int differenceInSeconds) throws Exception {
        Instant credentialExpiration = Instant.parse("2026-11-11T03:10:00Z");
        Instant signatureExpiration = credentialExpiration.plusSeconds(differenceInSeconds);
        AwsSessionCredentials credentials = AwsSessionCredentials.builder()
                .accessKeyId("test-access").secretAccessKey("test-secret").sessionToken("test-token")
                .expirationTime(credentialExpiration).build();
        when(credentialsProvider.resolveCredentials()).thenReturn(credentials);
        when(presignedRequest.url()).thenReturn(URI.create("https://example.com/image").toURL());
        when(presignedRequest.expiration()).thenReturn(signatureExpiration);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);

        SignedS3ImageUrl signedUrl = imageUrlProvider.sign(OBJECT_KEY);

        Instant expected = differenceInSeconds < 0 ? signatureExpiration : credentialExpiration;
        assertThat(signedUrl.accessUrl().expiresAt()).isEqualTo(expected);
        assertThat(signedUrl.credentialExpirationKnown()).isTrue();
        ArgumentCaptor<GetObjectPresignRequest> request = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(s3Presigner).presignGetObject(request.capture());
        assertThat(request.getValue().getObjectRequest().overrideConfiguration().orElseThrow()
                .credentialsProvider().orElseThrow().resolveCredentials()).isSameAs(credentials);
        verify(credentialsProvider).resolveCredentials();
    }

    @Test
    @DisplayName("자격 만료가 알려지지 않은 주소는 캐시에 재사용할 수 없다")
    void doNotTreatMissingExpirationAsPermanentCredentials() throws Exception {
        when(credentialsProvider.resolveCredentials())
                .thenReturn(AwsBasicCredentials.create("test-access", "test-secret"));
        when(presignedRequest.url()).thenReturn(URI.create("https://example.com/image").toURL());
        when(presignedRequest.expiration()).thenReturn(Instant.parse("2026-11-11T03:15:00Z"));
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);

        SignedS3ImageUrl signedUrl = imageUrlProvider.sign(OBJECT_KEY);

        assertThat(signedUrl.credentialExpirationKnown()).isFalse();
        assertThat(signedUrl.canReuseAt(Instant.parse("2026-11-11T03:00:00Z"))).isFalse();
        assertThat(signedUrl.accessUrl().expiresAt()).isEqualTo(presignedRequest.expiration());
    }

    @Test
    @DisplayName("실제 SDK는 회전 전의 자격 스냅샷과 만료를 함께 사용한다")
    void signWithTheResolvedSnapshotWhenCredentialsCanRotate() {
        Instant expiration = Instant.now().plusSeconds(300);
        AwsSessionCredentials first = sessionCredentials("first-access", "first-token", expiration);
        AwsSessionCredentials rotated = sessionCredentials(
                "rotated-access", "rotated-token", expiration.plusSeconds(900)
        );
        when(credentialsProvider.resolveCredentials()).thenReturn(first, rotated);

        try (S3Presigner sdkPresigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(credentialsProvider).build()) {
            S3ImageUrlProvider signer = new S3ImageUrlProvider(sdkPresigner, properties,
                    new S3FailureReporter(new S3ExceptionTranslator(), externalApiLogger), credentialsProvider);

            SignedS3ImageUrl signed = signer.sign(OBJECT_KEY);

            assertThat(signed.accessUrl().expiresAt()).isEqualTo(expiration);
            assertThat(signed.accessUrl().url().getQuery())
                    .contains("X-Amz-Credential=first-access/", "X-Amz-Security-Token=first-token");
            verify(credentialsProvider).resolveCredentials();
        }
    }

    private AwsSessionCredentials sessionCredentials(String accessKeyId, String token, Instant expiration) {
        return AwsSessionCredentials.builder().accessKeyId(accessKeyId).secretAccessKey("test-secret")
                .sessionToken(token).expirationTime(expiration).build();
    }
}
