package com.harudle.generation.adapter.out.r2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageLookupBudget;
import com.harudle.generation.diary.service.port.ImageLookupBudgetExceededException;
import com.harudle.generation.diary.service.port.BackupStorageException.FailureType;
import com.harudle.generation.diary.service.port.dto.BackupObjectMetadata;
import com.harudle.generation.diary.service.port.dto.BackupUploadResult;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class R2BackupObjectStorageTest {

    private static final String KEY = "harudle/generated/diary-images/dev/diary-id/image.png";
    private static final byte[] BYTES = "original".getBytes(StandardCharsets.UTF_8);
    private S3Client client;
    private S3Presigner presigner;
    private R2BackupObjectStorage storage;

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        R2StorageProperties properties = properties();
        presigner = S3Presigner.builder().region(Region.of("auto"))
                .endpointOverride(properties.endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        properties.accessKeyId(), properties.secretAccessKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        storage = new R2BackupObjectStorage(client, presigner, properties, new ExternalApiLogger());
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @ParameterizedTest
    @CsvSource({"image.png,image/png", "image.jpg,image/jpeg", "image.webp,image/webp"})
    @DisplayName("원본 키, MIME과 바이트를 그대로 조건부 업로드한다")
    void preserveOriginalOnUpload(String filename, String contentType) throws IOException {
        String key = KEY.replace("image.png", filename);
        assertThat(storage.uploadIfAbsent(key, image(BYTES, contentType))).isEqualTo(BackupUploadResult.UPLOADED);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(), body.capture());
        assertThat(request.getValue().bucket()).isEqualTo("test-backup");
        assertThat(request.getValue().key()).isEqualTo(key);
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(request.getValue().contentType()).isEqualTo(contentType);
        assertThat(request.getValue().contentLength()).isEqualTo(BYTES.length);
        assertThat(request.getValue().storageClass()).isNull();
        assertThat(request.getValue().checksumSHA256()).isNull();
        try (var stream = body.getValue().contentStreamProvider().newStream()) {
            assertThat(stream.readAllBytes()).containsExactly(BYTES);
        }
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("412 응답은 기존 파일로 처리하며 HEAD나 삭제를 하지 않는다")
    void preserveExistingObject() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(s3Error(412, "PreconditionFailed"));

        assertThat(storage.uploadIfAbsent(KEY, image(BYTES, "image/png")))
                .isEqualTo(BackupUploadResult.ALREADY_EXISTS);
        verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    @DisplayName("409 충돌은 기존 파일 확인으로 간주하지 않고 실패를 전달한다")
    void doNotTreatConditionalConflictAsExisting() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(s3Error(409, "ConditionalRequestConflict"));

        assertFailure(() -> storage.uploadIfAbsent(KEY, image(BYTES, "image/png")), FailureType.PROVIDER_ERROR);
        verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verifyNoMoreInteractions(client);
    }

    @Test
    void objectAndBucketHeadShareRemainingBudget() {
        AtomicLong time = new AtomicLong();
        ImageLookupBudget budget = new ImageLookupBudget(Duration.ofSeconds(2), time::get);
        when(client.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            time.addAndGet(Duration.ofMillis(1500).toNanos());
            throw S3Exception.builder().statusCode(404).build();
        });
        assertThat(storage.findMetadata(KEY, budget)).isEmpty();

        ArgumentCaptor<HeadObjectRequest> object = ArgumentCaptor.forClass(HeadObjectRequest.class);
        ArgumentCaptor<HeadBucketRequest> bucket = ArgumentCaptor.forClass(HeadBucketRequest.class);
        verify(client).headObject(object.capture());
        verify(client).headBucket(bucket.capture());
        assertThat(object.getValue().overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofSeconds(2));
        assertThat(bucket.getValue().overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofMillis(500));
    }

    @Test
    void expiredObjectHeadCannotStartBucketHead() {
        AtomicLong time = new AtomicLong();
        ImageLookupBudget budget = new ImageLookupBudget(Duration.ofSeconds(2), time::get);
        when(client.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            time.addAndGet(Duration.ofSeconds(2).toNanos());
            throw S3Exception.builder().statusCode(404).build();
        });
        assertThatThrownBy(() -> storage.findMetadata(KEY, budget))
                .isInstanceOf(ImageLookupBudgetExceededException.class);
        verify(client, never()).headBucket(any(HeadBucketRequest.class));
    }

    @Test
    void expiredBudgetCannotStartObjectHead() {
        AtomicLong time = new AtomicLong();
        ImageLookupBudget budget = new ImageLookupBudget(Duration.ofSeconds(2), time::get);
        time.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThatThrownBy(() -> storage.findMetadata(KEY, budget))
                .isInstanceOf(ImageLookupBudgetExceededException.class);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("원본 메타데이터를 SDK 타입과 분리해 반환한다")
    void readMetadata() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength((long) BYTES.length).contentType("image/png").eTag("\"opaque-etag\"").build());

        assertThat(storage.findMetadata(KEY)).contains(new BackupObjectMetadata(
                KEY, MediaType.IMAGE_PNG, BYTES.length, "\"opaque-etag\""
        ));
        ArgumentCaptor<HeadObjectRequest> request = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(client).headObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("test-backup");
        assertThat(request.getValue().key()).isEqualTo(KEY);
    }

    @Test
    @DisplayName("NoSuchKey 404는 파일 없음으로 처리한다")
    void distinguishMissingObject() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(s3Error(404, "NoSuchKey"));
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(s3Error(404, "NoSuchKey"));

        assertThat(storage.findMetadata(KEY)).isEmpty();
        assertThat(storage.download(KEY)).isEmpty();
        verify(client, never()).headBucket(any(HeadBucketRequest.class));
    }

    @Test
    @DisplayName("오류 본문 없는 HEAD 404는 버킷 존재를 확인한 뒤 파일 없음으로 처리한다")
    void confirmBucketForAmbiguousHead404() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        when(client.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());

        assertThat(storage.findMetadata(KEY)).isEmpty();
        ArgumentCaptor<HeadBucketRequest> request = ArgumentCaptor.forClass(HeadBucketRequest.class);
        verify(client).headBucket(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("test-backup");
    }

    @ParameterizedTest
    @CsvSource({"404,CONFIGURATION_ERROR", "403,AUTHORIZATION_ERROR"})
    @DisplayName("HEAD 404 뒤 버킷 확인 실패를 파일 없음으로 숨기지 않는다")
    void propagateBucketFailure(int status, String type) {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        when(client.headBucket(any(HeadBucketRequest.class))).thenThrow(S3Exception.builder().statusCode(status).build());
        assertFailure(() -> storage.findMetadata(KEY), FailureType.valueOf(type));
    }

    @ParameterizedTest
    @CsvSource({
            "403,AccessDenied,AUTHORIZATION_ERROR",
            "403,SignatureDoesNotMatch,AUTHENTICATION_ERROR",
            "404,NoSuchBucket,CONFIGURATION_ERROR",
            "500,InternalError,PROVIDER_ERROR",
            "429,SlowDown,PROVIDER_ERROR"
    })
    @DisplayName("권한, 인증, 버킷 설정과 제공자 오류를 파일 없음과 구분한다")
    void classifyProviderFailures(int status, String code, String type) {
        S3Exception error = s3Error(status, code);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(error);
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(error);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(error);

        assertFailure(() -> storage.findMetadata(KEY), FailureType.valueOf(type));
        assertFailure(() -> storage.download(KEY), FailureType.valueOf(type));
        assertFailure(() -> storage.uploadIfAbsent(KEY, image(BYTES, "image/png")), FailureType.valueOf(type));
    }

    @Test
    @DisplayName("네트워크 오류를 파일 없음으로 숨기거나 기존 백업을 삭제하지 않는다")
    void propagateNetworkFailure() {
        SdkClientException error = SdkClientException.create("Connection reset");
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(error);
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(error);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(error);

        assertFailure(() -> storage.findMetadata(KEY), FailureType.CLIENT_ERROR);
        assertFailure(() -> storage.download(KEY), FailureType.CLIENT_ERROR);
        assertFailure(() -> storage.uploadIfAbsent(KEY, image(BYTES, "image/png")), FailureType.CLIENT_ERROR);
        verify(client).headObject(any(HeadObjectRequest.class));
        verify(client).getObject(any(GetObjectRequest.class));
        verify(client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({"image.png,image/png", "image.jpg,image/jpeg", "image.webp,image/webp"})
    @DisplayName("다운로드한 원본의 MIME과 바이트를 보존한다")
    void preserveDownloadedOriginal(String filename, String contentType) throws IOException {
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response(BYTES, BYTES.length, contentType));
        ReferenceImage original = storage.download(KEY.replace("image.png", filename)).orElseThrow();

        assertThat(original.mediaType().toString()).isEqualTo(contentType);
        assertThat(original.resource().getContentAsByteArray()).containsExactly(BYTES);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 9})
    @DisplayName("허용 범위를 벗어난 다운로드 응답은 스트림을 중단한다")
    void abortInvalidSizedResponse(long declaredLength) {
        var response = spy(response(BYTES, declaredLength, "image/png"));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response);

        assertFailure(() -> storage.download(KEY), FailureType.RESPONSE_PROCESSING_ERROR);
        verify(response).abort();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/octet-stream", "image/jpeg", "invalid-mime"})
    @DisplayName("원본 확장자와 다른 응답 MIME은 거절한다")
    void rejectInvalidResponseMime(String contentType) {
        var response = spy(response(BYTES, BYTES.length, contentType));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response);

        assertFailure(() -> storage.download(KEY), FailureType.RESPONSE_PROCESSING_ERROR);
        verify(response).abort();
    }

    @Test
    @DisplayName("Content-Length가 실제 바이트와 다르면 잘못된 원본으로 반환하지 않는다")
    void rejectTruncatedResponse() {
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response(new byte[4], 8, "image/png"));
        assertFailure(() -> storage.download(KEY), FailureType.RESPONSE_PROCESSING_ERROR);
    }

    @Test
    @DisplayName("Content-Length가 작게 보고되어도 실제 다운로드 크기를 제한한다")
    void boundActualDownloadedBytes() throws IOException {
        var response = spy(response(new byte[32], 8, "image/png"));
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response);

        assertFailure(() -> storage.download(KEY), FailureType.RESPONSE_PROCESSING_ERROR);
        verify(response).readNBytes(9);
        verify(response).abort();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 9})
    @DisplayName("비어 있거나 최대 크기를 넘는 업로드를 전송 전에 거절한다")
    void rejectInvalidUploadSize(int size) {
        assertFailure(() -> storage.uploadIfAbsent(KEY, image(new byte[size], "image/png")),
                FailureType.REQUEST_VALIDATION_ERROR);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("키 확장자와 다른 MIME의 업로드를 전송 전에 거절한다")
    void rejectMismatchedUploadMime() {
        assertFailure(() -> storage.uploadIfAbsent(KEY, image(BYTES, "image/jpeg")),
                FailureType.REQUEST_VALIDATION_ERROR);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "harudle/generated/diary-images/prod/diary-id/image.png",
            "harudle/generated/diary-images/development/diary-id/image.png",
            "harudle/references/generation/dev/image.png",
            "harudle/generated/diary-images/dev/../prod/image.png",
            "harudle/generated/diary-images/dev/%2e%2e/prod/image.png",
            "harudle/generated/diary-images/dev//image.png",
            "harudle/generated/diary-images/dev/diary-id\\image.png",
            "harudle/generated/diary-images/dev/diary-id/image-960.webp",
            "harudle/generated/diary-images/dev/diary-id/image-240.webp",
            "harudle/generated/diary-images/dev/diary-id\n/image.png"
    })
    @DisplayName("다른 환경, 파생 이미지와 잘못된 경로는 모든 작업에서 거절한다")
    void rejectInvalidKey(String key) {
        assertFailure(() -> storage.findMetadata(key), FailureType.REQUEST_VALIDATION_ERROR);
        assertFailure(() -> storage.download(key), FailureType.REQUEST_VALIDATION_ERROR);
        assertFailure(() -> storage.uploadIfAbsent(key, image(BYTES, "image/png")), FailureType.REQUEST_VALIDATION_ERROR);
        assertFailure(() -> storage.createAccessUrl(key), FailureType.REQUEST_VALIDATION_ERROR);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("UTF-8 기준 1024바이트를 넘는 키를 거절한다")
    void rejectLongKey() {
        assertFailure(() -> storage.findMetadata(KEY.replace("diary-id", "가".repeat(350))),
                FailureType.REQUEST_VALIDATION_ERROR);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("R2 GET 서명 URL과 만료 시각을 발급하며 파일 존재 조회는 하지 않는다")
    void createSignedUrl() {
        Instant before = Instant.now();
        ImageAccessUrl accessUrl = storage.createAccessUrl(KEY);

        assertThat(accessUrl.url().getHost()).isEqualTo("example.r2.cloudflarestorage.com");
        assertThat(accessUrl.url().getPath()).isEqualTo("/test-backup/" + KEY);
        assertThat(accessUrl.url().getQuery()).contains("X-Amz-Expires=900", "r2-test-key");
        assertThat(accessUrl.expiresAt()).isBetween(before.plusSeconds(899), Instant.now().plusSeconds(901));
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("작업 키와 결과를 로그에 남기고 서명 URL, 자격 증명과 오류 메시지는 노출하지 않는다")
    void logResultsWithoutSecrets() {
        List<Logger> loggers = List.of(
                (Logger) LoggerFactory.getLogger(R2BackupObjectStorage.class),
                (Logger) LoggerFactory.getLogger(ExternalApiLogger.class)
        );
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        loggers.forEach(logger -> logger.addAppender(appender));
        try {
            storage.uploadIfAbsent(KEY, image(BYTES, "image/png"));
            ImageAccessUrl signed = storage.createAccessUrl(KEY);
            when(client.headObject(any(HeadObjectRequest.class))).thenThrow(SdkClientException.create(
                    "r2-test-secret " + signed.url()));
            assertFailure(() -> storage.findMetadata(KEY), FailureType.CLIENT_ERROR);
            assertFailure(() -> storage.findMetadata("secret\n" + signed.url()), FailureType.REQUEST_VALIDATION_ERROR);
        } finally {
            loggers.forEach(logger -> logger.detachAppender(appender));
            appender.stop();
        }
        String logs = appender.list.stream().map(event -> event.getFormattedMessage()
                + event.getKeyValuePairs() + (event.getThrowableProxy() == null ? ""
                : ThrowableProxyUtil.asString(event.getThrowableProxy()))).reduce("", String::concat);
        assertThat(logs).contains(KEY, "result=UPLOADED", "result=URL_CREATED", "result=FAILED", "objectKey=invalid")
                .doesNotContain("r2-test-key", "r2-test-secret", "X-Amz-", "https://", "secret\n");
    }

    private static GeneratedImage image(byte[] bytes, String contentType) {
        return new GeneratedImage(new ByteArrayResource(bytes), MediaType.parseMediaType(contentType));
    }

    private static ResponseInputStream<GetObjectResponse> response(byte[] bytes, long size, String contentType) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength(size).contentType(contentType).build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    private static S3Exception s3Error(int status, String code) {
        S3Exception.Builder builder = S3Exception.builder();
        builder.statusCode(status);
        builder.requestId("test-request");
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build());
        return (S3Exception) builder.build();
    }

    private static void assertFailure(Runnable action, FailureType type) {
        BackupStorageException failure = catchThrowableOfType(action::run, BackupStorageException.class);
        assertThat(failure.failureType()).isEqualTo(type);
    }

    private static R2StorageProperties properties() {
        return new R2StorageProperties(true, "dev", URI.create("https://example.r2.cloudflarestorage.com"),
                "test-backup", "r2-test-key", "r2-test-secret", Duration.ofMinutes(15), DataSize.ofBytes(8), Duration.ofSeconds(2));
    }
}
