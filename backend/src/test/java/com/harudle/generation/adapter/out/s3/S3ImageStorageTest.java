package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.harudle.common.logging.ExternalApiFailure;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ExtendWith(MockitoExtension.class)
class S3ImageStorageTest {

    private static final UUID GENERATION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String OBJECT_KEY =
            "generated/diary-images/550e8400-e29b-41d4-a716-446655440000/image.png";
    private static final int MAX_OBJECT_SIZE_BYTES = 10;

    @Mock
    private S3Client s3Client;

    @Mock
    private ExternalApiLogger externalApiLogger;

    @Mock
    private ImageVariantEncoder variantEncoder;

    private S3ImageStorage imageStorage;

    @Test
    void restoreUsesOriginalKeyAndConditionalWrite() {
        assertThat(imageStorage.restoreIfMissing(OBJECT_KEY, generatedImage())).isTrue();
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().key()).isEqualTo(OBJECT_KEY);
        assertThat(request.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void concurrentRecoveryDoesNotOverwriteOrDelete() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(412).build());
        assertThat(imageStorage.restoreIfMissing(OBJECT_KEY, generatedImage())).isFalse();
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void uncertainRecoveryNeverDeletesObject() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(503).build());
        assertThatThrownBy(() -> imageStorage.restoreIfMissing(OBJECT_KEY, generatedImage()))
                .isInstanceOf(ImageStorageException.class);
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void onlyNotFoundIsTreatedAsMissing() {
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build())
                .thenThrow(S3Exception.builder().statusCode(403).build());
        assertThat(imageStorage.exists(OBJECT_KEY)).isFalse();
        assertThatThrownBy(() -> imageStorage.exists(OBJECT_KEY)).isInstanceOf(ImageStorageException.class);
    }

    @BeforeEach
    void setUp() {
        S3StorageProperties properties = new S3StorageProperties(
                "test-bucket",
                "ap-northeast-2",
                "generated/diary-images",
                DataSize.ofBytes(MAX_OBJECT_SIZE_BYTES),
                Duration.ofMinutes(10)
        );
        imageStorage = new S3ImageStorage(
                s3Client,
                properties,
                new ImageObjectKeyFactory(properties),
                new S3FailureReporter(new S3ExceptionTranslator(), externalApiLogger),
                variantEncoder
        );
    }

    @Test
    @DisplayName("이미 WebP인 생성 이미지는 변환 없이 저장한다")
    void storeGeneratedImage() throws IOException {
        byte[] imageBytes = "generated".getBytes(StandardCharsets.UTF_8);
        GeneratedImage generatedImage = new GeneratedImage(
                new ByteArrayResource(imageBytes),
                MediaType.parseMediaType("image/webp")
        );
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String storedObjectKey = imageStorage.store(GENERATION_ID, generatedImage);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest request = requestCaptor.getValue();
        assertThat(storedObjectKey).startsWith("generated/diary-images/" + GENERATION_ID + "/")
                .endsWith("/image.webp");
        assertThat(request.bucket()).isEqualTo("test-bucket");
        assertThat(request.key()).isEqualTo(storedObjectKey);
        assertThat(request.contentType()).isEqualTo("image/webp");
        assertThat(request.contentLength()).isEqualTo(imageBytes.length);
        assertThat(bodyCaptor.getValue().optionalContentLength()).contains((long) imageBytes.length);
        assertThat(bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes())
                .isEqualTo(imageBytes);
    }

    @Test
    void rejectMissingGeneratedImageBeforeSelectingStoragePath() {
        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID, null))
                .isInstanceOf(ImageStorageException.class)
                .hasRootCauseMessage("저장할 생성 이미지가 필요합니다.");
        verifyNoInteractions(s3Client, variantEncoder);
    }

    @Test
    void storesBothOptimizedVariantsAndDeletesBoth() {
        GeneratedImage detail = new GeneratedImage(
                new ByteArrayResource("detail".getBytes(StandardCharsets.UTF_8)),
                MediaType.parseMediaType("image/webp")
        );
        GeneratedImage thumbnail = new GeneratedImage(
                new ByteArrayResource("thumb".getBytes(StandardCharsets.UTF_8)),
                MediaType.parseMediaType("image/webp")
        );
        when(variantEncoder.encode(any(GeneratedImage.class)))
                .thenReturn(new ImageVariantEncoder.Variants(detail, thumbnail));

        String detailKey = imageStorage.store(GENERATION_ID, generatedImage());
        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(puts.capture(), any(RequestBody.class));
        assertThat(detailKey).endsWith("/image-960.webp");
        assertThat(puts.getAllValues().get(0).key()).isEqualTo(
                detailKey.replace("image-960.webp", "image-240.webp")
        );
        assertThat(puts.getAllValues().get(1).key()).isEqualTo(detailKey);
        assertThat(puts.getAllValues()).allSatisfy(request ->
                assertThat(request.contentType()).isEqualTo("image/webp")
        );

        imageStorage.delete(detailKey);
        ArgumentCaptor<DeleteObjectRequest> deletes = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(2)).deleteObject(deletes.capture());
        assertThat(deletes.getAllValues().get(0).key()).isEqualTo(detailKey);
        assertThat(deletes.getAllValues().get(1).key()).isEqualTo(puts.getAllValues().get(0).key());
    }

    @Test
    void failedDetailUploadCleansUpStoredThumbnail() {
        GeneratedImage webp = new GeneratedImage(
                new ByteArrayResource("webp".getBytes(StandardCharsets.UTF_8)),
                MediaType.parseMediaType("image/webp")
        );
        when(variantEncoder.encode(any(GeneratedImage.class)))
                .thenReturn(new ImageVariantEncoder.Variants(webp, webp));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build())
                .thenThrow(SdkClientException.builder().message("detail upload failed").build());

        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID, generatedImage()))
                .isInstanceOf(ImageStorageException.class);

        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<DeleteObjectRequest> deletes = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(2)).putObject(puts.capture(), any(RequestBody.class));
        verify(s3Client).deleteObject(deletes.capture());
        assertThat(deletes.getValue().key()).isEqualTo(puts.getAllValues().get(0).key());
    }

    @Test
    @DisplayName("S3 저장 성공 후 입력 스트림 닫기에 실패해도 저장 객체를 삭제하지 않는다")
    void doNotCompensateWhenInputStreamCloseFailsAfterSuccessfulPut() throws IOException {
        byte[] imageBytes = "generated".getBytes(StandardCharsets.UTF_8);
        InputStream inputStream = mock(InputStream.class);
        IOException closeCause = new IOException("stream close failure");
        doThrow(closeCause).when(inputStream).close();
        Resource closeFailingResource = new ByteArrayResource(imageBytes) {
            @Override
            public InputStream getInputStream() {
                return inputStream;
            }
        };
        GeneratedImage generatedImage = new GeneratedImage(
                closeFailingResource, MediaType.parseMediaType("image/webp")
        );
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID, generatedImage))
                .isInstanceOf(ImageStorageException.class)
                .hasCause(closeCause);

        var callOrder = inOrder(s3Client, inputStream);
        callOrder.verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        callOrder.verify(inputStream).close();
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        verify(externalApiLogger).warn(
                eq(new ExternalApiFailure("s3", "put_object", "CLIENT_ERROR", null, null, null)),
                eq(closeCause)
        );
    }

    @Test
    @DisplayName("S3 저장 결과를 확정할 수 없으면 삭제를 주기적 정리에 맡긴다")
    void deferUnknownStoreOutcomeCleanup() {
        GeneratedImage generatedImage = unconvertedImage();
        SdkClientException storeCause = SdkClientException.builder()
                .message("unknown store outcome")
                .build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(storeCause);

        ImageStorageException thrown = catchThrowableOfType(
                () -> imageStorage.store(GENERATION_ID, generatedImage),
                ImageStorageException.class
        );

        assertThat(thrown)
                .hasMessageContaining("S3 이미지 저장")
                .hasMessageContaining("generated/diary-images/" + GENERATION_ID)
                .hasCause(storeCause);
        verify(externalApiLogger).warn(
                eq(new ExternalApiFailure("s3", "put_object", "CLIENT_ERROR", null, null, null)),
                eq(storeCause)
        );
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("실패 후 다시 업로드하면 이전 PUT과 다른 키를 사용한다")
    void retryUsesAnotherObjectKey() {
        GeneratedImage generatedImage = unconvertedImage();
        SdkClientException storeCause = SdkClientException.builder()
                .message("unknown store outcome")
                .build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(storeCause).thenReturn(PutObjectResponse.builder().build());

        ImageStorageException thrown = catchThrowableOfType(
                () -> imageStorage.store(GENERATION_ID, generatedImage),
                ImageStorageException.class
        );

        assertThat(thrown)
                .hasMessageContaining("S3 이미지 저장")
                .hasCause(storeCause);
        String result = imageStorage.store(GENERATION_ID, generatedImage);
        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, org.mockito.Mockito.times(2)).putObject(requests.capture(), any(RequestBody.class));
        assertThat(requests.getAllValues().get(0).key()).isNotEqualTo(result);
        assertThat(requests.getAllValues().get(1).key()).isEqualTo(result);
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("S3 업로드 호출 전 실패에는 기존 Object Key를 삭제하지 않는다")
    void doNotCompensateBeforePutAttempt() {
        byte[] imageBytes = "generated".getBytes(StandardCharsets.UTF_8);
        Resource unreadableOnOpenResource = new ByteArrayResource(imageBytes) {
            @Override
            public ByteArrayInputStream getInputStream() throws IOException {
                throw new IOException("stream open failure");
            }
        };
        GeneratedImage generatedImage = new GeneratedImage(
                unreadableOnOpenResource, MediaType.parseMediaType("image/webp")
        );

        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID, generatedImage))
                .isInstanceOf(ImageStorageException.class)
                .hasMessageContaining("S3 이미지 저장")
                .hasRootCauseMessage("stream open failure");
        verify(externalApiLogger).error(
                eq(new ExternalApiFailure(
                        "s3",
                        "put_object",
                        "REQUEST_PREPARATION_ERROR",
                        null,
                        null,
                        null
                )),
                any(IOException.class)
        );
        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("S3 이미지 객체를 메모리 Resource로 읽고 응답 스트림을 닫는다")
    void loadReferenceImage() throws IOException {
        byte[] imageBytes = "reference".getBytes(StandardCharsets.UTF_8);
        CloseTrackingInputStream inputStream = new CloseTrackingInputStream(imageBytes);
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(inputStream, imageBytes.length, "image/png"));

        ReferenceImage referenceImage = imageStorage.load("prompt-assets/reference.png");

        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo("prompt-assets/reference.png");
        assertThat(referenceImage.mediaType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(referenceImage.resource().getContentAsByteArray()).isEqualTo(imageBytes);
        assertThat(inputStream.isClosed()).isTrue();
    }

    @Test
    @DisplayName("S3 응답 스트림 읽기 실패는 일시적인 클라이언트 오류로 기록한다")
    void logResponseStreamReadFailureAsWarn() {
        IOException cause = new IOException("response stream read failure");
        InputStream inputStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw cause;
            }
        };
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(inputStream, 1, "image/png"));

        assertThatThrownBy(() -> imageStorage.load("prompt-assets/reference.png"))
                .isInstanceOf(ImageStorageException.class)
                .hasCause(cause);
        verify(externalApiLogger).warn(
                eq(new ExternalApiFailure("s3", "get_object", "CLIENT_ERROR", null, null, null)),
                eq(cause)
        );
    }

    @Test
    @DisplayName("S3 응답 스트림의 SDK 전송 실패는 일시적인 클라이언트 오류로 기록한다")
    void logResponseStreamSdkFailureAsWarn() {
        SdkClientException cause = SdkClientException.builder()
                .message("response stream transfer failure")
                .build();
        InputStream inputStream = new InputStream() {
            @Override
            public int read() {
                throw cause;
            }
        };
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(inputStream, 1, "image/png"));

        assertThatThrownBy(() -> imageStorage.load("prompt-assets/reference.png"))
                .isInstanceOf(ImageStorageException.class)
                .hasCause(cause);
        verify(externalApiLogger).warn(
                eq(new ExternalApiFailure("s3", "get_object", "CLIENT_ERROR", null, null, null)),
                eq(cause)
        );
    }

    @Test
    @DisplayName("S3 응답 스트림 닫기 실패는 일시적인 클라이언트 오류로 기록한다")
    void logResponseStreamCloseFailureAsWarn() {
        byte[] imageBytes = "reference".getBytes(StandardCharsets.UTF_8);
        IOException cause = new IOException("response stream close failure");
        InputStream inputStream = new ByteArrayInputStream(imageBytes) {
            @Override
            public void close() throws IOException {
                throw cause;
            }
        };
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(inputStream, imageBytes.length, "image/png"));

        assertThatThrownBy(() -> imageStorage.load("prompt-assets/reference.png"))
                .isInstanceOf(ImageStorageException.class)
                .hasCause(cause);
        verify(externalApiLogger).warn(
                eq(new ExternalApiFailure("s3", "get_object", "CLIENT_ERROR", null, null, null)),
                eq(cause)
        );
    }

    @Test
    @DisplayName("S3 이미지 객체를 삭제한다")
    void deleteImage() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        imageStorage.delete(OBJECT_KEY);

        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo(OBJECT_KEY);
    }

    @Test
    @DisplayName("설정된 최대 크기를 넘는 생성 이미지는 S3에 저장하지 않는다")
    void rejectOversizedGeneratedImage() {
        byte[] oversizedImage = new byte[MAX_OBJECT_SIZE_BYTES + 1];
        GeneratedImage generatedImage = new GeneratedImage(
                new ByteArrayResource(oversizedImage),
                MediaType.IMAGE_PNG
        );

        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID, generatedImage))
                .isInstanceOf(ImageStorageException.class)
                .hasMessageContaining("S3 이미지 저장")
                .hasRootCauseMessage("S3 이미지 객체 크기가 허용 범위를 벗어났습니다.");
        verify(externalApiLogger).warn(
                eq(new ExternalApiFailure(
                        "s3",
                        "put_object",
                        "REQUEST_VALIDATION_ERROR",
                        null,
                        null,
                        null
                )),
                any(IllegalArgumentException.class)
        );
        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("S3 응답의 실제 이미지 크기가 제한을 넘으면 조회에 실패한다")
    void rejectOversizedResponseBody() {
        byte[] oversizedImage = new byte[MAX_OBJECT_SIZE_BYTES + 1];
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(
                        new CloseTrackingInputStream(oversizedImage),
                        MAX_OBJECT_SIZE_BYTES,
                        "image/png"
                ));

        assertThatThrownBy(() -> imageStorage.load("prompt-assets/reference.png"))
                .isInstanceOf(ImageStorageException.class)
                .hasRootCauseMessage("S3 이미지 객체 크기가 허용 범위를 벗어났습니다.");
        verify(externalApiLogger).error(
                eq(new ExternalApiFailure(
                        "s3",
                        "get_object",
                        "RESPONSE_PROCESSING_ERROR",
                        null,
                        null,
                        null
                )),
                any(IllegalArgumentException.class)
        );
    }

    @Test
    @DisplayName("S3 객체의 Content-Type이 이미지가 아니면 조회에 실패한다")
    void rejectNonImageContentType() {
        byte[] objectBytes = "not-image".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(
                        new CloseTrackingInputStream(objectBytes),
                        objectBytes.length,
                        "text/plain"
                ));

        assertThatThrownBy(() -> imageStorage.load("prompt-assets/reference.txt"))
                .isInstanceOf(ImageStorageException.class)
                .hasMessageContaining("S3 이미지 조회")
                .hasRootCauseMessage("S3 객체의 Content-Type은 구체적인 image/* 타입이어야 합니다.");
    }

    @Test
    @DisplayName("AWS SDK 오류를 이미지 저장소 예외로 변환한다")
    void translateSdkException() {
        SdkClientException cause = SdkClientException.builder()
                .message("connection failure")
                .build();
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(cause);

        assertThatThrownBy(() -> imageStorage.load("prompt-assets/reference.png"))
                .isInstanceOf(ImageStorageException.class)
                .hasMessageContaining("S3 이미지 조회")
                .hasMessageContaining("prompt-assets/reference.png")
                .hasCause(cause);
        verify(externalApiLogger).warn(
                eq(new ExternalApiFailure("s3", "get_object", "CLIENT_ERROR", null, null, null)),
                eq(cause)
        );
    }

    @Test
    @DisplayName("AWS credentials provider chain의 자격 증명 해석 실패는 ERROR로 기록한다")
    void logCredentialsResolutionFailureAsError() {
        AwsCredentialsProviderChain credentialsProviderChain = AwsCredentialsProviderChain.of(
                () -> {
                    throw SdkClientException.builder()
                            .message("credentials unavailable")
                            .build();
                }
        );
        SdkClientException cause = catchThrowableOfType(
                credentialsProviderChain::resolveCredentials,
                SdkClientException.class
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(cause);

        assertThatThrownBy(() -> imageStorage.load("prompt-assets/reference.png"))
                .isInstanceOf(ImageStorageException.class)
                .hasCause(cause);

        verify(externalApiLogger).error(
                eq(new ExternalApiFailure(
                        "s3",
                        "get_object",
                        "AUTHENTICATION_ERROR",
                        null,
                        null,
                        null
                )),
                eq(cause)
        );
    }

    @Test
    @DisplayName("S3 권한 오류는 AWS 상태와 요청 ID를 ERROR로 기록한다")
    void logAccessDeniedAsError() {
        S3Exception.Builder exceptionBuilder = S3Exception.builder();
        exceptionBuilder.message("sensitive bucket details");
        exceptionBuilder.statusCode(403);
        exceptionBuilder.requestId("request-123");
        exceptionBuilder.awsErrorDetails(AwsErrorDetails.builder()
                .serviceName("S3")
                .errorCode("AccessDenied")
                .build());
        S3Exception cause = (S3Exception) exceptionBuilder.build();
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(cause);

        assertThatThrownBy(() -> imageStorage.load("prompt-assets/reference.png"))
                .isInstanceOf(ImageStorageException.class)
                .hasCause(cause);

        verify(externalApiLogger).error(
                eq(new ExternalApiFailure(
                        "s3",
                        "get_object",
                        "AUTHORIZATION_ERROR",
                        "403",
                        "AccessDenied",
                        "request-123"
                )),
                eq(cause)
        );
    }

    @Test
    @DisplayName("AWS 오류 코드가 없는 일시적 S3 장애도 안전하게 WARN으로 기록한다")
    void logProviderFailureWithoutAwsErrorCode() {
        S3Exception cause = mock(S3Exception.class);
        when(cause.statusCode()).thenReturn(503);
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(cause);

        assertThatThrownBy(() -> imageStorage.load("prompt-assets/reference.png"))
                .isInstanceOf(ImageStorageException.class)
                .hasCause(cause);

        verify(externalApiLogger).warn(
                eq(new ExternalApiFailure(
                        "s3",
                        "get_object",
                        "PROVIDER_ERROR",
                        "503",
                        null,
                        null
                )),
                eq(cause)
        );
    }

    @Test
    @DisplayName("S3 삭제 오류를 이미지 저장소 예외로 변환한다")
    void translateDeleteException() {
        SdkClientException cause = SdkClientException.builder()
                .message("connection failure")
                .build();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(cause);

        assertThatThrownBy(() -> imageStorage.delete(OBJECT_KEY))
                .isInstanceOf(ImageStorageException.class)
                .hasMessageContaining("S3 이미지 삭제")
                .hasMessageContaining(OBJECT_KEY)
                .hasCause(cause);
        verify(externalApiLogger).warn(
                eq(new ExternalApiFailure("s3", "delete_object", "CLIENT_ERROR", null, null, null)),
                eq(cause)
        );
    }

    private static GeneratedImage generatedImage() {
        byte[] imageBytes = "generated".getBytes(StandardCharsets.UTF_8);
        return new GeneratedImage(
                new ByteArrayResource(imageBytes),
                MediaType.IMAGE_PNG
        );
    }

    private static GeneratedImage unconvertedImage() {
        return new GeneratedImage(
                new ByteArrayResource("generated".getBytes(StandardCharsets.UTF_8)),
                MediaType.parseMediaType("image/webp")
        );
    }

    private static ResponseInputStream<GetObjectResponse> responseStream(
            InputStream inputStream,
            long contentLength,
            String contentType
    ) {
        GetObjectResponse response = GetObjectResponse.builder()
                .contentLength(contentLength)
                .contentType(contentType)
                .build();
        return new ResponseInputStream<>(response, inputStream);
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {

        private boolean closed;

        private CloseTrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean isClosed() {
            return closed;
        }
    }
}
