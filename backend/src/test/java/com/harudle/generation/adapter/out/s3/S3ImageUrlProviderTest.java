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
import com.harudle.generation.configuration.S3StorageProperties;
import com.harudle.generation.service.port.ImageAccessUrl;
import com.harudle.generation.service.port.ImageStorageException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

@ExtendWith(MockitoExtension.class)
class S3ImageUrlProviderTest {

    private static final String OBJECT_KEY = "generated/diary-images/diary-id/image.png";
    private static final Duration ACCESS_URL_TTL = Duration.ofMinutes(15);

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private PresignedGetObjectRequest presignedRequest;

    @Mock
    private ExternalApiLogger externalApiLogger;

    private S3ImageUrlProvider imageUrlProvider;

    @BeforeEach
    void setUp() {
        S3StorageProperties properties = new S3StorageProperties(
                "test-bucket",
                "ap-northeast-2",
                "generated/diary-images",
                DataSize.ofMegabytes(20),
                ACCESS_URL_TTL
        );
        imageUrlProvider = new S3ImageUrlProvider(
                s3Presigner,
                properties,
                new S3FailureReporter(new S3ExceptionTranslator(), externalApiLogger)
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
        assertThat(accessUrl.url()).isEqualTo(url);
        assertThat(accessUrl.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("빈 Object Key는 S3 Presigner에 전달하지 않는다")
    void rejectBlankObjectKey() {
        assertThatThrownBy(() -> imageUrlProvider.createAccessUrl(" "))
                .isInstanceOf(ImageStorageException.class)
                .hasRootCauseMessage("이미지 Object Key가 필요합니다.");
        verify(externalApiLogger).error(
                eq(new ExternalApiFailure(
                        "s3",
                        "presign_get_object",
                        "REQUEST_PREPARATION_ERROR",
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
                .hasMessageContaining(OBJECT_KEY)
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
}
