package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.domain.ImageVariant;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

class S3ImageStorageRetryTest {

    private static final UUID GENERATION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String BUCKET = "retry-test-bucket";
    private static final String UPLOAD_TOKEN_HEADER = "x-amz-meta-harudle-upload-token";
    private static final String CONTENT_SHA256_HEADER = "x-amz-meta-harudle-content-sha256";
    private static final String CHECKSUM_HEADER = "x-amz-checksum-sha256";
    private static final byte[] ORIGINAL_BYTES = "original-png".getBytes(StandardCharsets.UTF_8);
    private static final byte[] THUMBNAIL_BYTES = "thumbnail-webp".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DETAIL_BYTES = "detail-webp".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("실제 SDK가 저장 응답 유실 후 재시도에서 412를 받아도 같은 업로드를 확인하면 성공한다")
    void confirmCommittedUploadAfterSdkRetryReturnsPreconditionFailed() {
        InMemoryS3 http = new InMemoryS3(false);
        try (S3Client sdk = s3Client(http)) {
            String detailKey = imageStorage(sdk).store(GENERATION_ID, originalImage());

            assertThat(http.objects).containsKey(detailKey);
            assertPreservedObjects(http);
            assertThat(http.events).containsExactly(
                    "PUT 200 image.png",
                    "PUT 200 image-240.webp",
                    "PUT committed, response lost image-960.webp",
                    "PUT 412 image-960.webp",
                    "HEAD 200 image-960.webp"
            );
        }
    }

    @Test
    @DisplayName("실제 SDK의 저장 재시도 뒤 HEAD가 거절되면 원본과 파생 이미지 모두 보존한다")
    void preserveAllObjectsWhenCommittedSdkRetryCannotBeVerified() {
        InMemoryS3 http = new InMemoryS3(true);
        try (S3Client sdk = s3Client(http)) {
            ImageStorageException failure = catchThrowableOfType(
                    () -> imageStorage(sdk).store(GENERATION_ID, originalImage()),
                    ImageStorageException.class
            );

            assertThat(failure).hasCauseInstanceOf(S3Exception.class);
            S3Exception putFailure = (S3Exception) failure.getCause();
            assertThat(putFailure.statusCode()).isEqualTo(412);
            assertThat(putFailure.numAttempts()).isEqualTo(2);
            assertPreservedObjects(http);
            assertThat(http.events).containsExactly(
                    "PUT 200 image.png",
                    "PUT 200 image-240.webp",
                    "PUT committed, response lost image-960.webp",
                    "PUT 412 image-960.webp",
                    "HEAD 403 image-960.webp"
            );
        }
    }

    private static S3Client s3Client(InMemoryS3 http) {
        return S3Client.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy")))
                .endpointOverride(URI.create("http://localhost:54321"))
                .forcePathStyle(true)
                .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .overrideConfiguration(config -> config.retryStrategy(StandardRetryStrategy.builder()
                        .maxAttempts(2)
                        .backoffStrategy(BackoffStrategy.retryImmediately())
                        .throttlingBackoffStrategy(BackoffStrategy.retryImmediately())
                        .build()))
                .httpClient(http)
                .build();
    }

    private static S3ImageStorage imageStorage(S3Client sdk) {
        S3StorageProperties properties = new S3StorageProperties(
                BUCKET, "ap-northeast-2", "dev",
                "harudle/generated/diary-images/dev", "harudle/references/generation/dev",
                DataSize.ofMegabytes(1), Duration.ofMinutes(5)
        );
        ImageUploadPreparer preparer = new ImageUploadPreparer(new ImageObjectKeyFactory(properties),
                image -> Map.of(
                        ImageVariant.THUMBNAIL, webpImage(THUMBNAIL_BYTES),
                        ImageVariant.DETAIL, webpImage(DETAIL_BYTES)
                ));
        return new S3ImageStorage(sdk, properties, preparer,
                new S3FailureReporter(new S3ExceptionTranslator(), mock(ExternalApiLogger.class)));
    }

    private static GeneratedImage originalImage() {
        return new GeneratedImage(new ByteArrayResource(ORIGINAL_BYTES), MediaType.IMAGE_PNG);
    }

    private static GeneratedImage webpImage(byte[] bytes) {
        return new GeneratedImage(new ByteArrayResource(bytes), MediaType.parseMediaType("image/webp"));
    }

    private static void assertPreservedObjects(InMemoryS3 http) {
        assertThat(http.objects).hasSize(3);
        assertThat(http.objects.keySet().stream().map(S3ImageStorageRetryTest::filename).toList())
                .containsExactlyInAnyOrder("image.png", "image-240.webp", "image-960.webp");
        assertThat(http.events).noneMatch(event -> event.startsWith("DELETE"));
        assertThat(http.putAttempts).hasSize(4);
        assertThat(http.objects.values().stream().map(StoredObject::body).toList())
                .containsExactly(ORIGINAL_BYTES, THUMBNAIL_BYTES, DETAIL_BYTES);
    }

    private static String filename(String key) {
        return key.substring(key.lastIndexOf('/') + 1);
    }

    private static String sha256(byte[] bytes) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private record StoredObject(String token, String metadataChecksum, String checksum,
                                String contentType, long contentLength, byte[] body) {
    }

    /** SDK의 HTTP 전송만 대신하며 실제 네트워크에 연결하지 않는다. */
    private static final class InMemoryS3 implements SdkHttpClient {

        private final boolean rejectHead;
        private final Map<String, StoredObject> objects = new LinkedHashMap<>();
        private final List<StoredObject> putAttempts = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private boolean detailResponseLost;

        private InMemoryS3(boolean rejectHead) {
            this.rejectHead = rejectHead;
        }

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() throws IOException {
                    String path = request.httpRequest().encodedPath();
                    assertThat(path).startsWith("/" + BUCKET + "/");
                    String key = path.substring(BUCKET.length() + 2);
                    return switch (request.httpRequest().method()) {
                        case PUT -> put(key, request);
                        case HEAD -> head(key, request.httpRequest());
                        case DELETE -> delete(key);
                        default -> throw new AssertionError("Unexpected S3 method: " + request.httpRequest().method());
                    };
                }

                @Override
                public void abort() {
                }
            };
        }

        private HttpExecuteResponse put(String key, HttpExecuteRequest request) throws IOException {
            SdkHttpRequest httpRequest = request.httpRequest();
            assertThat(header(httpRequest, "If-None-Match")).isEqualTo("*");
            byte[] bytes;
            try (InputStream stream = request.contentStreamProvider().orElseThrow().newStream()) {
                bytes = stream.readAllBytes();
            }
            StoredObject uploaded = new StoredObject(
                    header(httpRequest, UPLOAD_TOKEN_HEADER), header(httpRequest, CONTENT_SHA256_HEADER),
                    header(httpRequest, CHECKSUM_HEADER), header(httpRequest, "Content-Type"),
                    Long.parseLong(header(httpRequest, "Content-Length")), bytes
            );
            assertThat(uploaded.token()).isNotBlank();
            assertThat(uploaded.metadataChecksum()).isEqualTo(uploaded.checksum());
            assertThat(uploaded.checksum()).isEqualTo(sha256(bytes));
            assertThat(uploaded.contentLength()).isEqualTo(bytes.length);
            putAttempts.add(uploaded);

            StoredObject existing = objects.get(key);
            if (existing != null) {
                assertThat(filename(key)).isEqualTo("image-960.webp");
                assertThat(uploaded.token()).isEqualTo(existing.token());
                assertThat(uploaded.metadataChecksum()).isEqualTo(existing.metadataChecksum());
                assertThat(uploaded.checksum()).isEqualTo(existing.checksum());
                assertThat(uploaded.contentType()).isEqualTo(existing.contentType());
                assertThat(uploaded.contentLength()).isEqualTo(existing.contentLength());
                assertThat(uploaded.body()).isEqualTo(existing.body());
                events.add("PUT 412 " + filename(key));
                return response(412, Map.of(),
                        "<Error><Code>PreconditionFailed</Code><Message>Object already exists</Message></Error>");
            }

            objects.put(key, uploaded);
            if (filename(key).equals("image-960.webp") && !detailResponseLost) {
                detailResponseLost = true;
                events.add("PUT committed, response lost " + filename(key));
                throw new IOException("Connection closed after S3 committed the object");
            }
            events.add("PUT 200 " + filename(key));
            return response(200, Map.of(), "");
        }

        private HttpExecuteResponse head(String key, SdkHttpRequest request) {
            assertThat(header(request, "x-amz-checksum-mode")).isEqualTo("ENABLED");
            assertThat(objects).containsKey(key);
            if (rejectHead) {
                events.add("HEAD 403 " + filename(key));
                return response(403, Map.of(), "");
            }
            StoredObject stored = objects.get(key);
            events.add("HEAD 200 " + filename(key));
            return response(200, Map.of(
                    UPLOAD_TOKEN_HEADER, stored.token(),
                    CONTENT_SHA256_HEADER, stored.metadataChecksum(),
                    CHECKSUM_HEADER, stored.checksum(),
                    "x-amz-checksum-type", "FULL_OBJECT",
                    "Content-Length", Long.toString(stored.contentLength()),
                    "Content-Type", stored.contentType()
            ), "");
        }

        private HttpExecuteResponse delete(String key) {
            objects.remove(key);
            events.add("DELETE " + filename(key));
            return response(204, Map.of(), "");
        }

        private static String header(SdkHttpRequest request, String name) {
            return request.firstMatchingHeader(name).orElseThrow(() -> new AssertionError("Missing header: " + name));
        }

        private static HttpExecuteResponse response(int status, Map<String, String> headers, String xml) {
            SdkHttpResponse.Builder response = SdkHttpResponse.builder()
                    .statusCode(status)
                    .putHeader("Content-Type", "application/xml")
                    .putHeader("ETag", "\"retry-test-etag\"")
                    .putHeader("x-amz-request-id", "retry-test-request");
            headers.forEach(response::putHeader);
            return HttpExecuteResponse.builder()
                    .response(response.build())
                    .responseBody(AbortableInputStream.create(
                            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))))
                    .build();
        }

        @Override
        public void close() {
        }
    }
}
