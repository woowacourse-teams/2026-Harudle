package com.harudle.generation.adapter.out.r2;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.diary.service.port.dto.BackupUploadResult;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class R2BackupObjectStorageSdkTest {

    private static final String KEY = "harudle/generated/diary-images/dev/diary-id/image.png";
    private static final byte[] BYTES = "original-png".getBytes(StandardCharsets.UTF_8);
    private static final R2StorageProperties PROPERTIES = new R2StorageProperties(
            true, "dev", URI.create("https://example.r2.cloudflarestorage.com"), "test-backup",
            "test-key", "test-secret", Duration.ofMinutes(15), DataSize.ofMegabytes(20)
    );

    @Test
    @DisplayName("실제 SDK 요청이 원본 키와 MIME을 보존하고 R2에서 지원하지 않는 체크섬을 보내지 않는다")
    void preserveOriginalThroughSdkRequests() throws IOException {
        InMemoryR2 http = new InMemoryR2(false);
        try (S3Client client = client(http); S3Presigner presigner = presigner()) {
            R2BackupObjectStorage storage = storage(client, presigner);

            assertThat(storage.uploadIfAbsent(KEY, image(BYTES))).isEqualTo(BackupUploadResult.UPLOADED);
            assertThat(storage.findMetadata(KEY).orElseThrow().size()).isEqualTo(BYTES.length);
            assertThat(storage.download(KEY).orElseThrow().resource().getContentAsByteArray()).containsExactly(BYTES);
            assertThat(storage.uploadIfAbsent(KEY, image(new byte[]{1, 2, 3})))
                    .isEqualTo(BackupUploadResult.ALREADY_EXISTS);
            assertThat(http.objects.get(KEY).bytes()).containsExactly(BYTES);
            assertThat(http.events).containsExactly("PUT 200", "HEAD 200", "GET 200", "PUT 412");
        }
    }

    @Test
    @DisplayName("실제 SDK의 응답 유실 후 재시도가 412이면 기존 원본을 보존하고 존재 결과를 반환한다")
    void preserveObjectAfterCommittedPutResponseIsLost() {
        InMemoryR2 http = new InMemoryR2(true);
        try (S3Client client = client(http); S3Presigner presigner = presigner()) {
            assertThat(storage(client, presigner).uploadIfAbsent(KEY, image(BYTES)))
                    .isEqualTo(BackupUploadResult.ALREADY_EXISTS);
            assertThat(http.objects.get(KEY).bytes()).containsExactly(BYTES);
            assertThat(http.events).containsExactly("PUT response lost", "PUT 412");
        }
    }

    @Test
    @DisplayName("두 업로드가 동시에 같은 키를 저장해도 한 원본만 생성하고 기존 원본을 덮어쓰지 않는다")
    void preserveOneOriginalDuringConcurrentUpload() throws Exception {
        InMemoryR2 http = new InMemoryR2(false, 2);
        byte[] competing = "other-original".getBytes(StandardCharsets.UTF_8);
        try (S3Client client = client(http); S3Presigner presigner = presigner();
             var executor = Executors.newFixedThreadPool(2)) {
            R2BackupObjectStorage storage = storage(client, presigner);
            var first = executor.submit(() -> storage.uploadIfAbsent(KEY, image(BYTES)));
            var second = executor.submit(() -> storage.uploadIfAbsent(KEY, image(competing)));
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(BackupUploadResult.UPLOADED, BackupUploadResult.ALREADY_EXISTS);
            assertThat(http.objects).hasSize(1);
            byte[] expected = first.get() == BackupUploadResult.UPLOADED ? BYTES : competing;
            assertThat(http.objects.get(KEY).bytes()).containsExactly(expected);
            assertThat(http.events).containsExactlyInAnyOrder("PUT 200", "PUT 412");
        }
    }

    private static GeneratedImage image(byte[] bytes) {
        return new GeneratedImage(new ByteArrayResource(bytes), MediaType.IMAGE_PNG);
    }

    private static R2BackupObjectStorage storage(S3Client client, S3Presigner presigner) {
        return new R2BackupObjectStorage(client, presigner, PROPERTIES, new ExternalApiLogger());
    }

    private static S3Client client(SdkHttpClient http) {
        return S3Client.builder().endpointOverride(PROPERTIES.endpoint()).region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(config -> config.retryStrategy(StandardRetryStrategy.builder()
                        .maxAttempts(2).backoffStrategy(BackoffStrategy.retryImmediately())
                        .throttlingBackoffStrategy(BackoffStrategy.retryImmediately()).build()))
                .httpClient(http).build();
    }

    private static S3Presigner presigner() {
        return S3Presigner.builder().endpointOverride(PROPERTIES.endpoint()).region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }

    private record StoredObject(byte[] bytes, String contentType) {
    }

    /** SDK 본체를 사용하고 HTTP 전송만 대체한다. 실제 R2와 AWS에는 접근하지 않는다. */
    private static final class InMemoryR2 implements SdkHttpClient {

        private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean loseNextPutResponse;
        private final CountDownLatch writersReady;

        private InMemoryR2(boolean loseNextPutResponse) {
            this(loseNextPutResponse, 1);
        }

        private InMemoryR2(boolean loseNextPutResponse, int simultaneousWriters) {
            this.loseNextPutResponse = new AtomicBoolean(loseNextPutResponse);
            this.writersReady = new CountDownLatch(simultaneousWriters);
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() throws IOException {
                    var httpRequest = request.httpRequest();
                    assertThat(httpRequest.host()).isEqualTo(PROPERTIES.endpoint().getHost());
                    assertThat(httpRequest.encodedPath()).startsWith("/test-backup/");
                    String key = httpRequest.encodedPath().substring("/test-backup/".length());
                    return switch (httpRequest.method()) {
                        case PUT -> put(key, request);
                        case HEAD -> read(key, true);
                        case GET -> read(key, false);
                        default -> throw new AssertionError("Unexpected method: " + httpRequest.method());
                    };
                }

                @Override
                public void abort() {
                }
            };
        }

        private HttpExecuteResponse put(String key, HttpExecuteRequest request) throws IOException {
            var httpRequest = request.httpRequest();
            assertThat(httpRequest.firstMatchingHeader("If-None-Match")).contains("*");
            assertThat(httpRequest.firstMatchingHeader("Content-Type")).contains("image/png");
            assertThat(httpRequest.firstMatchingHeader("x-amz-storage-class")).isEmpty();
            assertThat(httpRequest.headers().keySet()).noneMatch(name ->
                    name.toLowerCase(java.util.Locale.ROOT).startsWith("x-amz-checksum-"));
            assertThat(httpRequest.firstMatchingHeader("Content-Encoding")).isEmpty();
            byte[] bytes;
            try (var body = request.contentStreamProvider().orElseThrow().newStream()) {
                bytes = body.readAllBytes();
            }
            assertThat(httpRequest.firstMatchingHeader("Content-Length")).contains(Integer.toString(bytes.length));
            writersReady.countDown();
            try {
                if (!writersReady.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Concurrent writers did not arrive");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            if (objects.putIfAbsent(key, new StoredObject(bytes, "image/png")) != null) {
                events.add("PUT 412");
                return response(412, Map.of("Content-Type", "application/xml"),
                        "<Error><Code>PreconditionFailed</Code></Error>".getBytes(StandardCharsets.UTF_8));
            }
            if (loseNextPutResponse.compareAndSet(true, false)) {
                events.add("PUT response lost");
                throw new IOException("Connection closed after committing object");
            }
            events.add("PUT 200");
            return response(200, Map.of(), new byte[0]);
        }

        private HttpExecuteResponse read(String key, boolean head) {
            StoredObject stored = objects.get(key);
            assertThat(stored).isNotNull();
            events.add(head ? "HEAD 200" : "GET 200");
            return response(200, Map.of("Content-Type", stored.contentType(),
                            "Content-Length", Integer.toString(stored.bytes().length)),
                    head ? new byte[0] : stored.bytes());
        }

        private static HttpExecuteResponse response(int status, Map<String, String> headers, byte[] bytes) {
            SdkHttpResponse.Builder response = SdkHttpResponse.builder().statusCode(status)
                    .putHeader("ETag", "\"opaque-etag\"").putHeader("x-amz-request-id", "r2-test-request");
            headers.forEach(response::putHeader);
            return HttpExecuteResponse.builder().response(response.build())
                    .responseBody(AbortableInputStream.create(new ByteArrayInputStream(bytes))).build();
        }

        @Override
        public void close() {
        }
    }
}
