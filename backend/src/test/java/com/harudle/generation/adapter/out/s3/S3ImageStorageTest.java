package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.harudle.generation.diary.domain.ImageVariant;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.ChecksumType;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
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
            "harudle/generated/diary-images/dev/550e8400-e29b-41d4-a716-446655440000/image.png";
    private static final String DETAIL_KEY =
            "harudle/generated/diary-images/dev/550e8400-e29b-41d4-a716-446655440000/image-960.webp";
    private static final String THUMBNAIL_KEY =
            "harudle/generated/diary-images/dev/550e8400-e29b-41d4-a716-446655440000/image-240.webp";
    private static final int MAX_OBJECT_SIZE_BYTES = 10;

    @Mock
    private S3Client s3Client;

    @Mock
    private ExternalApiLogger externalApiLogger;

    @Mock
    private ImageVariantEncoder variantEncoder;

    private S3ImageStorage imageStorage;

    @ParameterizedTest
    @ValueSource(ints = {409, 412})
    @DisplayName("일반 저장이 기존 파일과 충돌하면 덮어쓰거나 삭제하지 않는다")
    void storeConflictPreservesExistingObject(int status) {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(status).build());

        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID, unconvertedImage()))
                .isInstanceOf(ImageStorageException.class);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("업로드 중 기존 파일과 충돌하면 이번 요청에서 새로 저장한 파일만 정리한다")
    void storeConflictOnlyCleansNewlyCreatedObjects() {
        List<ImageUploadPreparer.Upload> uploads = List.of(
                new ImageUploadPreparer.Upload(OBJECT_KEY, generatedImage()),
                new ImageUploadPreparer.Upload(THUMBNAIL_KEY, unconvertedImage()),
                new ImageUploadPreparer.Upload(DETAIL_KEY, unconvertedImage())
        );
        S3ImageStorage storage = storageWithUploads(uploads);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build(), PutObjectResponse.builder().build())
                .thenThrow(S3Exception.builder().statusCode(412).build());
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().metadata(Map.of("harudle-upload-token", "another-upload"))
                        .build());

        assertThatThrownBy(() -> storage.store(GENERATION_ID, generatedImage()))
                .isInstanceOf(ImageStorageException.class);

        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(3)).putObject(puts.capture(), any(RequestBody.class));
        assertThat(puts.getAllValues()).allSatisfy(request -> assertThat(request.ifNoneMatch()).isEqualTo("*"));
        ArgumentCaptor<DeleteObjectRequest> deletes = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(2)).deleteObject(deletes.capture());
        assertThat(deletes.getAllValues()).extracting(DeleteObjectRequest::key)
                .containsExactly(OBJECT_KEY, THUMBNAIL_KEY).doesNotContain(DETAIL_KEY);
    }

    @Test
    @DisplayName("상세 이미지 복구가 실패해도 원본과 기존 썸네일을 삭제하지 않는다")
    void failedDetailRecoveryPreservesOriginalAndThumbnail() {
        stubOptimizedImages("detail", "thumb");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build())
                .thenThrow(S3Exception.builder().statusCode(503).build());

        assertThatThrownBy(() -> imageStorage.restoreOptimizedIfMissing(DETAIL_KEY, generatedImage()))
                .isInstanceOf(ImageStorageException.class);

        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(puts.capture(), any(RequestBody.class));
        assertThat(puts.getAllValues()).extracting(PutObjectRequest::key).containsExactly(OBJECT_KEY, DETAIL_KEY);
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("기존 썸네일이 있으면 복구 중 그 파일을 보존한다")
    void recoveryPreservesExistingThumbnailOnConflict() {
        stubOptimizedImages("detail", "thumb");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build(), PutObjectResponse.builder().build())
                .thenThrow(S3Exception.builder().statusCode(412).build());

        assertThat(imageStorage.restoreOptimizedIfMissing(DETAIL_KEY, generatedImage())).isTrue();

        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(3)).putObject(puts.capture(), any(RequestBody.class));
        assertThat(puts.getAllValues().getLast().key()).isEqualTo(THUMBNAIL_KEY);
        assertThat(puts.getAllValues().getLast().ifNoneMatch()).isEqualTo("*");
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

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

    @Test
    @DisplayName("상세 이미지와 썸네일은 기존 파일을 삭제하지 않고 누락분만 복구한다")
    void missingDetailRestoresWithoutDeletingExistingThumbnail() {
        stubOptimizedImages("detail", "thumb");

        assertThat(imageStorage.restoreOptimizedIfMissing(DETAIL_KEY, generatedImage())).isTrue();

        var order = inOrder(s3Client);
        order.verify(s3Client).putObject(org.mockito.ArgumentMatchers.<PutObjectRequest>argThat(
                request -> request.key().equals(DETAIL_KEY) && request.ifNoneMatch().equals("*")),
                any(RequestBody.class));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        order.verify(s3Client).putObject(org.mockito.ArgumentMatchers.<PutObjectRequest>argThat(
                request -> request.key().equals(THUMBNAIL_KEY) && request.ifNoneMatch().equals("*")),
                any(RequestBody.class));
    }

    @Test
    void conversionFailureKeepsExistingThumbnail() {
        CwebpConversionException conversionFailure = new CwebpConversionException(
                1, "Error! Cannot read input picture file <input>"
        );
        when(variantEncoder.encode(any()))
                .thenThrow(new IllegalStateException("conversion failed", conversionFailure));

        assertThatThrownBy(() -> imageStorage.restoreOptimizedIfMissing(DETAIL_KEY, generatedImage()))
                .isInstanceOf(ImageStorageException.class);

        verify(externalApiLogger).error(
                eq(new ExternalApiFailure("s3", "put_object", "CWEBP_INPUT_ERROR", null, null, null)),
                any(IllegalStateException.class)
        );
        verifyNoInteractions(s3Client);
    }

    @Test
    void recoveryKeepsStoredOriginalAndBuildsVariantsFromIt() throws IOException {
        stubOptimizedImages("detail", "thumb");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(412).build())
                .thenReturn(PutObjectResponse.builder().build());
        byte[] savedOriginal = "original".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(new ByteArrayInputStream(savedOriginal), savedOriginal.length, "image/png"));

        assertThat(imageStorage.restoreOptimizedIfMissing(DETAIL_KEY, generatedImage())).isTrue();

        ArgumentCaptor<GeneratedImage> encoded = ArgumentCaptor.forClass(GeneratedImage.class);
        verify(variantEncoder, times(2)).encode(encoded.capture());
        assertThat(encoded.getAllValues().getLast().resource().getContentAsByteArray()).isEqualTo(savedOriginal);
        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(3)).putObject(puts.capture(), any(RequestBody.class));
        assertThat(puts.getAllValues()).extracting(PutObjectRequest::key)
                .containsExactly(OBJECT_KEY, DETAIL_KEY, THUMBNAIL_KEY);
        assertThat(puts.getAllValues().getFirst().ifNoneMatch()).isEqualTo("*");
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void thumbnailRecoveryPrefersOriginalToDetail() throws IOException {
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build())
                .thenReturn(software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder().build());
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(new ByteArrayInputStream(original), original.length, "image/png"));
        stubOptimizedImages("unused", "thumb");

        assertThat(imageStorage.restoreMissingThumbnail(DETAIL_KEY)).isTrue();

        ArgumentCaptor<GetObjectRequest> get = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(get.capture());
        assertThat(get.getValue().key()).isEqualTo(OBJECT_KEY);
        ArgumentCaptor<GeneratedImage> encoded = ArgumentCaptor.forClass(GeneratedImage.class);
        verify(variantEncoder).encode(encoded.capture());
        assertThat(encoded.getValue().resource().getContentAsByteArray()).isEqualTo(original);
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void thumbnailIsRebuiltFromSavedDetailWithoutRegeneration() throws IOException {
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        byte[] savedDetail = "saved".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(new ByteArrayInputStream(savedDetail), savedDetail.length, "image/webp"));
        stubOptimizedImages("unused", "thumb");

        assertThat(imageStorage.restoreMissingThumbnail(DETAIL_KEY)).isTrue();

        ArgumentCaptor<GeneratedImage> encoderInput = ArgumentCaptor.forClass(GeneratedImage.class);
        verify(variantEncoder).encode(encoderInput.capture());
        assertThat(encoderInput.getValue().mediaType().toString()).isEqualTo("image/webp");
        assertThat(encoderInput.getValue().resource().getContentAsByteArray()).isEqualTo(savedDetail);
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().key()).isEqualTo(THUMBNAIL_KEY);
        assertThat(put.getValue().ifNoneMatch()).isEqualTo("*");
    }

    @Test
    void detailWriteConflictRepairsThumbnailFromStoredDetail() throws IOException {
        stubOptimizedImages("new", "newthumb");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build())
                .thenThrow(S3Exception.builder().statusCode(412).build())
                .thenReturn(PutObjectResponse.builder().build());
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenAnswer(invocation -> {
                    var request = invocation.getArgument(0,
                            software.amazon.awssdk.services.s3.model.HeadObjectRequest.class);
                    if (request.key().equals(THUMBNAIL_KEY)) {
                        throw S3Exception.builder().statusCode(404).build();
                    }
                    return software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder().build();
                });
        byte[] savedDetail = "saved".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(new ByteArrayInputStream(savedDetail), savedDetail.length, "image/webp"));

        assertThat(imageStorage.restoreOptimizedIfMissing(DETAIL_KEY, generatedImage())).isTrue();

        ArgumentCaptor<GeneratedImage> encoded = ArgumentCaptor.forClass(GeneratedImage.class);
        verify(variantEncoder, times(2)).encode(encoded.capture());
        assertThat(encoded.getAllValues().get(1).resource().getContentAsByteArray()).isEqualTo(savedDetail);
        ArgumentCaptor<GetObjectRequest> get = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(get.capture());
        assertThat(get.getValue().key()).isEqualTo(DETAIL_KEY);
        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(3)).putObject(puts.capture(), any(RequestBody.class));
        assertThat(puts.getAllValues()).extracting(PutObjectRequest::key)
                .containsExactly(OBJECT_KEY, DETAIL_KEY, THUMBNAIL_KEY);
    }

    @Test
    void failedThumbnailWriteCanBeRetriedFromStoredDetail() {
        stubOptimizedImages("detail", "thumb");
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build(), PutObjectResponse.builder().build())
                .thenThrow(S3Exception.builder().statusCode(503).build())
                .thenReturn(PutObjectResponse.builder().build());
        when(s3Client.headObject(any(software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        byte[] savedDetail = "detail".getBytes(StandardCharsets.UTF_8);
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(responseStream(new ByteArrayInputStream(savedDetail), savedDetail.length, "image/webp"));

        assertThatThrownBy(() -> imageStorage.restoreOptimizedIfMissing(DETAIL_KEY, generatedImage()))
                .isInstanceOf(ImageStorageException.class);
        assertThat(imageStorage.restoreMissingThumbnail(DETAIL_KEY)).isTrue();

        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(4)).putObject(puts.capture(), any(RequestBody.class));
        assertThat(puts.getAllValues()).extracting(PutObjectRequest::key)
                .containsExactly(OBJECT_KEY, DETAIL_KEY, THUMBNAIL_KEY, THUMBNAIL_KEY);
        verify(s3Client, never()).deleteObject(org.mockito.ArgumentMatchers.<DeleteObjectRequest>argThat(
                request -> request.key().equals(DETAIL_KEY)));
    }

    @BeforeEach
    void setUp() {
        S3StorageProperties properties = new S3StorageProperties(
                "test-bucket",
                "ap-northeast-2",
                "dev",
                "harudle/generated/diary-images/dev",
                "harudle/references/generation/dev",
                DataSize.ofBytes(MAX_OBJECT_SIZE_BYTES),
                Duration.ofMinutes(10)
        );
        imageStorage = new S3ImageStorage(
                s3Client,
                properties,
                new ImageUploadPreparer(new ImageObjectKeyFactory(properties), variantEncoder),
                new S3FailureReporter(new S3ExceptionTranslator(), externalApiLogger)
        );
    }

    @Test
    @DisplayName("객체 HEAD 404 후 버킷 조회 권한 오류가 나면 누락으로 간주하지 않는다")
    void rejectsUnknownStateAfterHeadNotFound() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).build());
        assertThatThrownBy(() -> imageStorage.exists(OBJECT_KEY)).isInstanceOf(ImageStorageException.class);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("NoSuchBucket는 객체 누락이 아닌 조회 실패로 처리한다")
    void rejectsMissingBucket() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).awsErrorDetails(
                        AwsErrorDetails.builder().errorCode("NoSuchBucket").build()).build());
        assertThatThrownBy(() -> imageStorage.exists(OBJECT_KEY)).isInstanceOf(ImageStorageException.class);
        verify(s3Client, never()).headBucket(any(HeadBucketRequest.class));
    }

    @Test
    @DisplayName("이미 WebP인 생성 이미지는 변환 없이 저장한다")
    void storeGeneratedImage() throws Exception {
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
        assertThat(storedObjectKey).startsWith("harudle/generated/diary-images/dev/" + GENERATION_ID + "/")
                .endsWith("/image.webp");
        assertThat(request.bucket()).isEqualTo("test-bucket");
        assertThat(request.key()).isEqualTo(storedObjectKey);
        assertThat(request.contentType()).isEqualTo("image/webp");
        assertThat(request.contentLength()).isEqualTo(imageBytes.length);
        String checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(imageBytes));
        assertThat(request.checksumSHA256()).isEqualTo(checksum);
        assertThat(request.metadata()).containsEntry("harudle-content-sha256", checksum);
        assertThat(UUID.fromString(request.metadata().get("harudle-upload-token"))).isNotNull();
        assertThat(bodyCaptor.getValue().optionalContentLength()).contains((long) imageBytes.length);
        assertThat(bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes())
                .isEqualTo(imageBytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"precondition", "transport", "provider"})
    @DisplayName("PUT 오류 후 동일 업로드와 실제 체크섬이 확인되면 저장을 성공으로 완료한다")
    void verifiedUploadCompletesAfterPutFailure(String failure) {
        Exception cause = switch (failure) {
            case "precondition" -> S3Exception.builder().statusCode(412).build();
            case "provider" -> S3Exception.builder().statusCode(503).build();
            default -> SdkClientException.builder().message("lost response").build();
        };
        AtomicReference<PutObjectRequest> failedPut = stubFailedDetail(cause);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenAnswer(invocation -> storedUpload(failedPut.get()).build());

        assertThat(storageWithThreeUploads().store(GENERATION_ID, generatedImage())).isEqualTo(DETAIL_KEY);

        ArgumentCaptor<HeadObjectRequest> head = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(head.capture());
        assertThat(head.getValue().key()).isEqualTo(DETAIL_KEY);
        assertThat(head.getValue().checksumMode()).isEqualTo(ChecksumMode.ENABLED);
        assertThat(head.getValue().overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofSeconds(10));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        verifyNoInteractions(externalApiLogger);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-checksum", "different-checksum", "missing-token", "metadata-digest",
            "length", "content-type", "composite"})
    @DisplayName("동일 업로드의 내용까지 검증할 수 없으면 앞선 파일을 보존한다")
    void insufficientUploadProofPreservesAllEarlierObjects(String mismatch) {
        S3Exception cause = (S3Exception) S3Exception.builder().statusCode(412).build();
        AtomicReference<PutObjectRequest> failedPut = stubFailedDetail(cause);
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectResponse.Builder head = storedUpload(failedPut.get());
            switch (mismatch) {
                case "missing-checksum" -> head.checksumSHA256((String) null);
                case "different-checksum" -> head.checksumSHA256("different");
                case "missing-token" -> head.metadata(Map.of("harudle-content-sha256",
                        failedPut.get().checksumSHA256()));
                case "metadata-digest" -> head.metadata(Map.of("harudle-upload-token",
                        failedPut.get().metadata().get("harudle-upload-token"), "harudle-content-sha256", "different"));
                case "length" -> head.contentLength(1L);
                case "content-type" -> head.contentType("image/png");
                case "composite" -> head.checksumType(ChecksumType.COMPOSITE);
                default -> throw new IllegalArgumentException(mismatch);
            }
            return head.build();
        });

        assertThatThrownBy(() -> storageWithThreeUploads().store(GENERATION_ID, generatedImage()))
                .isInstanceOf(ImageStorageException.class).hasCause(cause);

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 503})
    @DisplayName("HEAD로 PUT 결과를 확인할 수 없으면 원본과 썸네일도 삭제하지 않는다")
    void failedUploadVerificationPreservesEarlierObjects(int headStatus) {
        SdkClientException putCause = SdkClientException.builder().message("lost response").build();
        stubFailedDetail(putCause);
        S3Exception headCause = (S3Exception) S3Exception.builder().statusCode(headStatus).build();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(headCause);

        assertThatThrownBy(() -> storageWithThreeUploads().store(GENERATION_ID, generatedImage()))
                .isInstanceOf(ImageStorageException.class).hasCause(putCause);

        assertThat(putCause.getSuppressed()).containsExactly(headCause);
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("SDK 재시도 뒤의 권한 오류만으로 앞선 PUT이 실패했다고 판단하지 않는다")
    void rejectionAfterSdkRetryDoesNotTriggerCleanupWithoutProof() {
        S3Exception cause = (S3Exception) S3Exception.builder().statusCode(403).numAttempts(2).build();
        stubFailedDetail(cause);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).build());

        assertThatThrownBy(() -> storageWithThreeUploads().store(GENERATION_ID, generatedImage()))
                .isInstanceOf(ImageStorageException.class).hasCause(cause);

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403})
    @DisplayName("첫 번째 시도가 명확히 거부되면 이번 요청에서 앞서 저장한 파일만 정리한다")
    void singleAttemptRejectionCleansOnlyEarlierObjects(int status) {
        S3Exception cause = (S3Exception) S3Exception.builder().statusCode(status).numAttempts(1)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode(status == 403 ? "AccessDenied" : "InvalidArgument").build())
                .build();
        stubFailedDetail(cause);

        assertThatThrownBy(() -> storageWithThreeUploads().store(GENERATION_ID, generatedImage()))
                .isInstanceOf(ImageStorageException.class).hasCause(cause);

        ArgumentCaptor<DeleteObjectRequest> deletes = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(2)).deleteObject(deletes.capture());
        assertThat(deletes.getAllValues()).extracting(DeleteObjectRequest::key)
                .containsExactly(OBJECT_KEY, THUMBNAIL_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RequestTimeout", "RequestTimeoutException", "PriorRequestNotComplete",
            "InternalError", "SlowDown", "Throttling"})
    @DisplayName("HTTP 400이라도 타임아웃이나 throttling이면 앞선 파일을 삭제하지 않는다")
    void transientErrorCodeDoesNotBecomeDefiniteRejection(String code) {
        S3Exception cause = (S3Exception) S3Exception.builder().statusCode(400).numAttempts(1)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
        stubFailedDetail(cause);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(403).build());

        assertThatThrownBy(() -> storageWithThreeUploads().store(GENERATION_ID, generatedImage()))
                .isInstanceOf(ImageStorageException.class).hasCause(cause);

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void rejectMissingGeneratedImageBeforeSelectingStoragePath() {
        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID, null))
                .isInstanceOf(ImageStorageException.class)
                .hasRootCauseMessage("저장할 생성 이미지가 필요합니다.");
        verifyNoInteractions(s3Client, variantEncoder);
    }

    @Test
    @DisplayName("내용을 준비한 뒤 원본 Resource가 바뀌어도 PUT 본문과 체크섬은 같은 스냅샷을 사용한다")
    void requestBodyAndChecksumUseTheSameSnapshot() throws Exception {
        byte[] imageBytes = "generated".getBytes(StandardCharsets.UTF_8);
        int[] opens = {0};
        Resource mutableResource = new ByteArrayResource(imageBytes) {
            @Override
            public ByteArrayInputStream getInputStream() {
                opens[0]++;
                return new ByteArrayInputStream(opens[0] == 1 ? imageBytes : "different".getBytes(StandardCharsets.UTF_8));
            }
        };

        imageStorage.store(GENERATION_ID, new GeneratedImage(mutableResource, MediaType.parseMediaType("image/webp")));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(request.capture(), body.capture());
        assertThat(opens[0]).isEqualTo(1);
        assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes()).isEqualTo(imageBytes);
        assertThat(request.getValue().checksumSHA256()).isEqualTo(
                Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(imageBytes)));
    }

    @Test
    @DisplayName("선언된 Resource 크기와 실제 내용이 다르면 어떤 파일도 업로드하지 않는다")
    void mismatchedSourceLengthPreventsAllUploads() {
        Resource inconsistentResource = new ByteArrayResource("generated".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public long contentLength() {
                return 1;
            }
        };

        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID,
                new GeneratedImage(inconsistentResource, MediaType.parseMediaType("image/webp"))))
                .isInstanceOf(ImageStorageException.class)
                .hasRootCauseMessage("S3 이미지 객체의 선언된 크기와 실제 크기가 일치하지 않습니다.");

        verifyNoInteractions(s3Client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/png", "image/jpeg"})
    void storesOriginalAndVariantsAndDeletesAll(String mediaType) throws IOException {
        GeneratedImage detail = new GeneratedImage(
                new ByteArrayResource("detail".getBytes(StandardCharsets.UTF_8)),
                MediaType.parseMediaType("image/webp")
        );
        GeneratedImage thumbnail = new GeneratedImage(
                new ByteArrayResource("thumb".getBytes(StandardCharsets.UTF_8)),
                MediaType.parseMediaType("image/webp")
        );
        when(variantEncoder.encode(any(GeneratedImage.class)))
                .thenReturn(Map.of(ImageVariant.DETAIL, detail, ImageVariant.THUMBNAIL, thumbnail));

        GeneratedImage source = new GeneratedImage(generatedImage().resource(), MediaType.parseMediaType(mediaType));
        String detailKey = imageStorage.store(GENERATION_ID, source);
        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(3)).putObject(puts.capture(), any(RequestBody.class));
        assertThat(detailKey).endsWith("/image-960.webp");
        assertThat(puts.getAllValues().get(1).key()).isEqualTo(
                detailKey.replace("image-960.webp", "image-240.webp")
        );
        assertThat(puts.getAllValues().get(2).key()).isEqualTo(detailKey);
        assertThat(puts.getAllValues().subList(1, 3)).allSatisfy(request ->
                assertThat(request.contentType()).isEqualTo("image/webp")
        );

        String originalKey = detailKey.replace("image-960.webp", mediaType.equals("image/png") ? "image.png" : "image.jpg");
        assertThat(puts.getAllValues().getFirst().key()).isEqualTo(originalKey);
        assertThat(puts.getAllValues().getFirst().contentType()).isEqualTo(mediaType);
        ArgumentCaptor<RequestBody> bodies = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, times(3)).putObject(any(PutObjectRequest.class), bodies.capture());
        assertThat(bodies.getAllValues().getFirst().contentStreamProvider().newStream().readAllBytes())
                .isEqualTo(source.resource().getContentAsByteArray());
        imageStorage.delete(detailKey);
        ArgumentCaptor<DeleteObjectRequest> deletes = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(5)).deleteObject(deletes.capture());
        assertThat(deletes.getAllValues().getFirst().key()).isEqualTo(detailKey);
        assertThat(deletes.getAllValues()).extracting(DeleteObjectRequest::key).contains(originalKey, detailKey);
    }

    @Test
    void uncertainDetailUploadPreservesOriginalAndThumbnail() {
        GeneratedImage webp = new GeneratedImage(
                new ByteArrayResource("webp".getBytes(StandardCharsets.UTF_8)),
                MediaType.parseMediaType("image/webp")
        );
        when(variantEncoder.encode(any(GeneratedImage.class)))
                .thenReturn(Map.of(ImageVariant.DETAIL, webp, ImageVariant.THUMBNAIL, webp));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build(), PutObjectResponse.builder().build())
                .thenThrow(SdkClientException.builder().message("detail upload failed").build());

        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID, generatedImage()))
                .isInstanceOf(ImageStorageException.class);

        verify(s3Client, times(3)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3Client).headObject(any(HeadObjectRequest.class));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("저장소는 이미지 종류를 몰라도 세 파일을 순서대로 저장하고 대표 키를 반환한다")
    void storesAllImagesInUploadPlan() {
        List<String> keys = List.of("small.webp", "medium.webp", "primary.webp").stream()
                .map(filename -> "harudle/generated/diary-images/dev/" + filename).toList();
        S3ImageStorage storage = storageWithUploads(keys.stream()
                .map(key -> new ImageUploadPreparer.Upload(key, unconvertedImage()))
                .toList());

        assertThat(storage.store(GENERATION_ID, generatedImage())).isEqualTo(keys.getLast());

        ArgumentCaptor<PutObjectRequest> puts = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(3)).putObject(puts.capture(), any(RequestBody.class));
        assertThat(puts.getAllValues()).extracting(PutObjectRequest::key).containsExactlyElementsOf(keys);
        assertThat(puts.getAllValues()).allSatisfy(request -> assertThat(request.ifNoneMatch()).isEqualTo("*"));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("세 번째 업로드 결과가 불확실하면 앞서 저장한 두 파일도 보존한다")
    void uncertainThirdUploadPreservesSuccessfulUploads() {
        List<String> keys = List.of("small.webp", "medium.webp", "primary.webp").stream()
                .map(filename -> "harudle/generated/diary-images/dev/" + filename).toList();
        S3ImageStorage storage = storageWithUploads(keys.stream()
                .map(key -> new ImageUploadPreparer.Upload(key, unconvertedImage()))
                .toList());
        SdkClientException cause = SdkClientException.builder().message("unknown third upload outcome").build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build(), PutObjectResponse.builder().build())
                .thenThrow(cause);

        assertThatThrownBy(() -> storage.store(GENERATION_ID, generatedImage()))
                .isInstanceOf(ImageStorageException.class)
                .hasCause(cause);

        verify(s3Client).headObject(any(HeadObjectRequest.class));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("정리 중 삭제가 실패해도 나머지 파일을 정리하고 원래 업로드 오류를 유지한다")
    void cleanupFailureDoesNotStopCleanupOrMaskUploadFailure() {
        List<String> keys = List.of("small.webp", "medium.webp", "primary.webp").stream()
                .map(filename -> "harudle/generated/diary-images/dev/" + filename).toList();
        S3ImageStorage storage = storageWithUploads(keys.stream()
                .map(key -> new ImageUploadPreparer.Upload(key, unconvertedImage()))
                .toList());
        S3Exception uploadCause = (S3Exception) S3Exception.builder().statusCode(400).message("upload rejected")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidArgument").build())
                .numAttempts(1).build();
        SdkClientException cleanupCause = SdkClientException.builder().message("cleanup failed").build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build(), PutObjectResponse.builder().build())
                .thenThrow(uploadCause);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(cleanupCause)
                .thenReturn(DeleteObjectResponse.builder().build());

        ImageStorageException thrown = catchThrowableOfType(
                () -> storage.store(GENERATION_ID, generatedImage()), ImageStorageException.class
        );

        assertThat(thrown).hasCause(uploadCause);
        assertThat(thrown.getSuppressed()).hasSize(1);
        assertThat(thrown.getSuppressed()[0]).hasCause(cleanupCause);
        ArgumentCaptor<DeleteObjectRequest> deletes = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(2)).deleteObject(deletes.capture());
        assertThat(deletes.getAllValues()).extracting(DeleteObjectRequest::key)
                .containsExactly(keys.get(0), keys.get(1));
    }

    @Test
    @DisplayName("목록 뒤쪽 이미지가 크기 제한을 넘으면 어떤 파일도 업로드하지 않는다")
    void validatesEveryImageBeforeStartingUploads() {
        GeneratedImage oversizedImage = new GeneratedImage(
                new ByteArrayResource(new byte[MAX_OBJECT_SIZE_BYTES + 1]),
                MediaType.parseMediaType("image/webp")
        );
        S3ImageStorage storage = storageWithUploads(List.of(
                new ImageUploadPreparer.Upload("harudle/generated/diary-images/dev/small.webp", unconvertedImage()),
                new ImageUploadPreparer.Upload("harudle/generated/diary-images/dev/primary.webp", oversizedImage)
        ));

        assertThatThrownBy(() -> storage.store(GENERATION_ID, generatedImage()))
                .isInstanceOf(ImageStorageException.class)
                .hasRootCauseMessage("S3 이미지 객체 크기가 허용 범위를 벗어났습니다.");
        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("업로드 내용 준비 중 스트림 닫기에 실패하면 S3 업로드를 시작하지 않는다")
    void closeFailureWhilePreparingSnapshotPreventsAllUploads() throws IOException {
        byte[] imageBytes = "generated".getBytes(StandardCharsets.UTF_8);
        IOException closeCause = new IOException("stream close failure");
        Resource closeFailingResource = new ByteArrayResource(imageBytes) {
            @Override
            public ByteArrayInputStream getInputStream() {
                return new ByteArrayInputStream(imageBytes) {
                    @Override
                    public void close() throws IOException {
                        throw closeCause;
                    }
                };
            }
        };
        GeneratedImage generatedImage = new GeneratedImage(closeFailingResource,
                MediaType.parseMediaType("image/webp"));

        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID, generatedImage))
                .isInstanceOf(ImageStorageException.class)
                .hasCause(closeCause);

        verifyNoInteractions(s3Client);
        verify(externalApiLogger).error(
                eq(new ExternalApiFailure("s3", "put_object", "REQUEST_PREPARATION_ERROR", null, null, null)),
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
                .hasMessageNotContaining("harudle/generated/diary-images/dev/" + GENERATION_ID)
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

        ReferenceImage referenceImage = imageStorage.load("harudle/references/generation/dev/reference.png");

        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(requestCaptor.getValue().key()).isEqualTo("harudle/references/generation/dev/reference.png");
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

        assertThatThrownBy(() -> imageStorage.load("harudle/references/generation/dev/reference.png"))
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

        assertThatThrownBy(() -> imageStorage.load("harudle/references/generation/dev/reference.png"))
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

        assertThatThrownBy(() -> imageStorage.load("harudle/references/generation/dev/reference.png"))
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
    @DisplayName("상세 이미지 삭제가 실패해도 썸네일 삭제를 시도하고 두 오류를 보고한다")
    void deleteAttemptsEveryVariantAfterFailure() {
        String detailKey = "harudle/generated/diary-images/dev/550e8400-e29b-41d4-a716-446655440000/image-960.webp";
        SdkClientException detailFailure = SdkClientException.builder().message("detail delete failed").build();
        SdkClientException thumbnailFailure = SdkClientException.builder().message("thumbnail delete failed").build();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(detailFailure)
                .thenThrow(thumbnailFailure)
                .thenReturn(DeleteObjectResponse.builder().build());

        ImageStorageException exception = catchThrowableOfType(
                () -> imageStorage.delete(detailKey), ImageStorageException.class
        );

        assertThat(exception).hasCause(detailFailure);
        assertThat(exception.getSuppressed()).hasSize(1);
        assertThat(exception.getSuppressed()[0]).hasCause(thumbnailFailure);
        ArgumentCaptor<DeleteObjectRequest> requests = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(5)).deleteObject(requests.capture());
        assertThat(requests.getAllValues()).extracting(DeleteObjectRequest::key)
                .containsExactly(detailKey, detailKey.replace("image-960.webp", "image-240.webp"),
                        detailKey.replace("image-960.webp", "image.png"),
                        detailKey.replace("image-960.webp", "image.jpg"),
                        detailKey.replace("image-960.webp", "image.webp"));
    }

    @Test
    @DisplayName("보관할 원본이 S3 크기 제한을 넘으면 업로드를 시작하지 않는다")
    void rejectsOriginalAboveObjectLimit() {
        GeneratedImage source = new GeneratedImage(
                new ByteArrayResource(new byte[MAX_OBJECT_SIZE_BYTES + 1]), MediaType.IMAGE_PNG
        );
        GeneratedImage variant = new GeneratedImage(
                new ByteArrayResource(new byte[MAX_OBJECT_SIZE_BYTES]), MediaType.parseMediaType("image/webp")
        );
        when(variantEncoder.encode(source)).thenReturn(Map.of(
                ImageVariant.DETAIL, variant, ImageVariant.THUMBNAIL, variant
        ));

        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID, source))
                .isInstanceOf(ImageStorageException.class)
                .hasRootCauseMessage("S3 이미지 객체 크기가 허용 범위를 벗어났습니다.");
        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("변환 전 원본이 입력 크기 제한을 넘으면 이미지를 변환하거나 업로드하지 않는다")
    void rejectsSourceImageAboveInputLimit() throws IOException {
        Resource resource = mock(Resource.class);
        when(resource.isReadable()).thenReturn(true);
        when(resource.contentLength()).thenReturn(20L * 1024 * 1024 + 1);
        GeneratedImage source = new GeneratedImage(resource, MediaType.IMAGE_PNG);

        assertThatThrownBy(() -> imageStorage.store(GENERATION_ID, source))
                .isInstanceOf(ImageStorageException.class)
                .hasRootCauseMessage("입력 이미지 크기가 허용 범위를 벗어났습니다.");
        verifyNoInteractions(variantEncoder);
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

        assertThatThrownBy(() -> imageStorage.load("harudle/references/generation/dev/reference.png"))
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

        assertThatThrownBy(() -> imageStorage.load("harudle/references/generation/dev/reference.txt"))
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

        assertThatThrownBy(() -> imageStorage.load("harudle/references/generation/dev/reference.png"))
                .isInstanceOf(ImageStorageException.class)
                .hasMessageContaining("S3 이미지 조회")
                .hasMessageNotContaining("harudle/references/generation/dev/reference.png")
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

        assertThatThrownBy(() -> imageStorage.load("harudle/references/generation/dev/reference.png"))
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

        assertThatThrownBy(() -> imageStorage.load("harudle/references/generation/dev/reference.png"))
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

        assertThatThrownBy(() -> imageStorage.load("harudle/references/generation/dev/reference.png"))
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
                .hasMessageNotContaining(OBJECT_KEY)
                .hasCause(cause);
        verify(externalApiLogger).warn(
                eq(new ExternalApiFailure("s3", "delete_object", "CLIENT_ERROR", null, null, null)),
                eq(cause)
        );
    }

    private S3ImageStorage storageWithUploads(List<ImageUploadPreparer.Upload> uploads) {
        ImageUploadPreparer preparer = mock(ImageUploadPreparer.class);
        when(preparer.prepare(eq(GENERATION_ID), any(GeneratedImage.class)))
                .thenReturn(new ImageUploadPreparer.UploadPlan(uploads.getLast().objectKey(), uploads));
        S3StorageProperties properties = new S3StorageProperties(
                "test-bucket", "ap-northeast-2", "dev",
                "harudle/generated/diary-images/dev", "harudle/references/generation/dev",
                DataSize.ofBytes(MAX_OBJECT_SIZE_BYTES), Duration.ofMinutes(10)
        );
        return new S3ImageStorage(
                s3Client, properties, preparer,
                new S3FailureReporter(new S3ExceptionTranslator(), externalApiLogger)
        );
    }

    private S3ImageStorage storageWithThreeUploads() {
        return storageWithUploads(List.of(
                new ImageUploadPreparer.Upload(OBJECT_KEY, generatedImage()),
                new ImageUploadPreparer.Upload(THUMBNAIL_KEY, unconvertedImage()),
                new ImageUploadPreparer.Upload(DETAIL_KEY, unconvertedImage())
        ));
    }

    private AtomicReference<PutObjectRequest> stubFailedDetail(Exception exception) {
        AtomicReference<PutObjectRequest> failedPut = new AtomicReference<>();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            PutObjectRequest request = invocation.getArgument(0);
            if (request.key().equals(DETAIL_KEY)) {
                failedPut.set(request);
                throw exception;
            }
            return PutObjectResponse.builder().build();
        });
        return failedPut;
    }

    private static HeadObjectResponse.Builder storedUpload(PutObjectRequest request) {
        return HeadObjectResponse.builder()
                .metadata(request.metadata())
                .contentLength(request.contentLength())
                .contentType(request.contentType())
                .checksumSHA256(request.checksumSHA256())
                .checksumType(ChecksumType.FULL_OBJECT);
    }

    private static GeneratedImage generatedImage() {
        byte[] imageBytes = "generated".getBytes(StandardCharsets.UTF_8);
        return new GeneratedImage(
                new ByteArrayResource(imageBytes),
                MediaType.IMAGE_PNG
        );
    }

    private void stubOptimizedImages(String detail, String thumbnail) {
        GeneratedImage detailImage = new GeneratedImage(new ByteArrayResource(detail.getBytes(StandardCharsets.UTF_8)),
                MediaType.parseMediaType("image/webp"));
        GeneratedImage thumbnailImage = new GeneratedImage(
                new ByteArrayResource(thumbnail.getBytes(StandardCharsets.UTF_8)),
                MediaType.parseMediaType("image/webp"));
        when(variantEncoder.encode(any())).thenReturn(Map.of(
                ImageVariant.DETAIL, detailImage, ImageVariant.THUMBNAIL, thumbnailImage));
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
