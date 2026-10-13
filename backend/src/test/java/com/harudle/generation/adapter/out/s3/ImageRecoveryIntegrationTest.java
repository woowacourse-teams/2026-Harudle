package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.domain.ImageVariant;
import com.harudle.generation.diary.service.ImageRecoveryService;
import com.harudle.generation.diary.service.ImageBackupService;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** SDK 호출만 메모리로 대체하고 실제 S3 복구 어댑터와 cwebp 변환기를 연결한다. */
class ImageRecoveryIntegrationTest {
    private final String root = "harudle/generated/diary-images/dev/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/";
    private final String originalKey = root + "image.png";
    private final String detailKey = root + "image-960.webp";
    private final String thumbnailKey = root + "image-240.webp";
    private final S3Client client = mock(S3Client.class);
    private final BackupObjectStorage backup = mock(BackupObjectStorage.class);
    private final Map<String, Stored> objects = new ConcurrentHashMap<>();
    private final Map<String, Stored> backups = new ConcurrentHashMap<>();
    private final List<PutObjectRequest> puts = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> failOnce = new AtomicReference<>();
    private final AtomicReference<String> loseResponseOnce = new AtomicReference<>();
    private final CwebpImageVariantEncoder encoder = new CwebpImageVariantEncoder();
    private ImageRecoveryService service;
    private ImageBackupService backupService;
    private byte[] original;

    @BeforeEach
    void setUp() throws Exception {
        original = sourceImage("png", Color.ORANGE);
        backups.put(originalKey, new Stored(original, MediaType.IMAGE_PNG));
        var properties = new S3StorageProperties("test-source", "ap-northeast-2", "dev",
                "harudle/generated/diary-images/dev", "harudle/references/generation/dev",
                DataSize.ofMegabytes(20), Duration.ofMinutes(15));
        var storage = new S3ImageStorage(client, properties,
                new ImageUploadPreparer(new ImageObjectKeyFactory(properties), encoder),
                new S3FailureReporter(new S3ExceptionTranslator(), mock(ExternalApiLogger.class)));
        var backupProperties = new R2StorageProperties(true, "dev", URI.create("https://example.r2.cloudflarestorage.com"),
                "test-backup", "fake-key", "fake-secret", Duration.ofMinutes(15), DataSize.ofMegabytes(20),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        service = new ImageRecoveryService(storage, backup, properties, backupProperties, Clock.systemUTC());
        backupService = new ImageBackupService(storage, backup, properties, backupProperties, Clock.systemUTC());
        when(backup.findMetadata(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Stored value = backups.get(key);
            return value == null ? Optional.empty() : Optional.of(
                    new BackupObjectMetadata(key, value.mime(), value.bytes().length, null));
        });
        when(backup.download(anyString())).thenAnswer(invocation -> {
            Stored value = backups.get((String) invocation.getArgument(0));
            return value == null ? Optional.empty() : Optional.of(
                    new ReferenceImage(new ByteArrayResource(value.bytes()), value.mime()));
        });
        when(client.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(client.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest request = invocation.getArgument(0);
            Stored value = get(request.key());
            return HeadObjectResponse.builder().contentLength((long) value.bytes().length)
                    .contentType(value.mime().toString()).build();
        });
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
            GetObjectRequest request = invocation.getArgument(0);
            Stored value = get(request.key());
            return new ResponseInputStream<>(GetObjectResponse.builder()
                    .contentType(value.mime().toString()).contentLength((long) value.bytes().length).build(),
                    new ByteArrayInputStream(value.bytes()));
        });
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            PutObjectRequest request = invocation.getArgument(0);
            RequestBody body = invocation.getArgument(1);
            puts.add(request);
            assertThat(request.ifNoneMatch()).isEqualTo("*");
            String failureKey = failOnce.get();
            if (request.key().equals(failureKey) && failOnce.compareAndSet(failureKey, null)) {
                throw S3Exception.builder().statusCode(503).build();
            }
            byte[] bytes;
            try (InputStream stream = body.contentStreamProvider().newStream()) {
                bytes = stream.readAllBytes();
            }
            Stored value = new Stored(bytes, MediaType.parseMediaType(request.contentType()));
            if (objects.putIfAbsent(request.key(), value) != null) {
                throw S3Exception.builder().statusCode(412).build();
            }
            String responseKey = loseResponseOnce.get();
            if (request.key().equals(responseKey) && loseResponseOnce.compareAndSet(responseKey, null)) {
                throw SdkClientException.create("response lost after accepted upload");
            }
            return PutObjectResponse.builder().build();
        });
    }

    @ParameterizedTest
    @CsvSource({"png,false", "jpg,false", "webp,false", "png,true", "jpg,true", "webp,true"})
    void backsUpAndRestoresRealImagesWithMimeParameters(String extension, boolean optimized) throws Exception {
        String key = root + "image." + extension;
        String representativeKey = optimized ? detailKey : key;
        String contentType = switch (extension) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            default -> "image/webp";
        };
        MediaType mime = MediaType.parseMediaType(contentType + ";charset=UTF-8");
        byte[] bytes = extension.equals("webp")
                ? encoder.encode(new GeneratedImage(new ByteArrayResource(original), MediaType.IMAGE_PNG))
                        .get(ImageVariant.DETAIL).resource().getContentAsByteArray()
                : sourceImage(extension, Color.ORANGE);
        objects.put(key, new Stored(bytes, mime));
        backups.clear();
        when(backup.uploadIfAbsent(eq(key), any(GeneratedImage.class))).thenAnswer(invocation -> {
            GeneratedImage image = invocation.getArgument(1);
            Stored value = new Stored(image.resource().getContentAsByteArray(), image.mediaType());
            return backups.putIfAbsent(key, value) == null
                    ? BackupUploadResult.UPLOADED : BackupUploadResult.ALREADY_EXISTS;
        });

        var verified = backupService.backup(representativeKey).orElseThrow();
        assertThat(verified.originalKey()).isEqualTo(key);
        assertThat(verified.mediaType()).isEqualTo(mime);
        assertThat(backups.get(key).bytes()).isEqualTo(bytes);
        assertThat(backups.get(key).mime()).isEqualTo(mime);
        objects.remove(key);

        assertThat(service.recover(representativeKey, true).status()).isEqualTo("WOULD_RESTORE");
        assertThat(objects).isEmpty();
        var restored = service.recover(representativeKey, false);
        assertThat(restored.status()).isEqualTo("RESTORED");
        assertThat(restored.mime()).isEqualTo(mime.toString());
        assertThat(restored.sha256()).isEqualTo(verified.sha256());
        assertThat(objects.get(key).bytes()).isEqualTo(bytes);
        assertThat(objects.get(key).mime()).isEqualTo(mime);
        if (optimized) {
            assertWebp(objects.get(detailKey), 960);
            assertWebp(objects.get(thumbnailKey), 240);
        }
        int putCount = puts.size();
        assertThat(service.recover(representativeKey, false).status()).isEqualTo("ALREADY_EXISTS");
        assertThat(puts).hasSize(putCount);
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void restoresActualWebpFilesAndPreservesRepresentativeKeyOnRerun() throws Exception {
        assertThat(service.recover(detailKey, true).status()).isEqualTo("WOULD_RESTORE");
        assertThat(objects).isEmpty();
        assertThat(puts).isEmpty();
        assertThat(service.recover(detailKey, false).status()).isEqualTo("RESTORED");
        assertThat(objects.get(originalKey).bytes()).isEqualTo(original);
        assertWebp(objects.get(detailKey), 960);
        assertWebp(objects.get(thumbnailKey), 240);
        int putCount = puts.size();
        var saved = Map.copyOf(objects);
        assertThat(service.recover(detailKey, false).status()).isEqualTo("ALREADY_EXISTS");
        assertThat(objects).isEqualTo(saved);
        assertThat(puts).hasSize(putCount);
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));

        // 화면에서 열어 볼 수 있는 합성 테스트 결과를 빌드 산출물로 남긴다.
        Path preview = Path.of("build", "r2-recovery-preview");
        Files.createDirectories(preview);
        Files.write(preview.resolve("image.png"), original);
        Files.write(preview.resolve("image-960.webp"), objects.get(detailKey).bytes());
        Files.write(preview.resolve("image-240.webp"), objects.get(thumbnailKey).bytes());
    }

    @Test
    void preservesExistingNormalThumbnailDuringPartialFailureAndRetry() throws Exception {
        var other = new GeneratedImage(new ByteArrayResource(sourceImage("png", Color.BLUE)), MediaType.IMAGE_PNG);
        byte[] normalThumbnail = encoder.encode(other).get(ImageVariant.THUMBNAIL).resource().getContentAsByteArray();
        objects.put(thumbnailKey, new Stored(normalThumbnail, MediaType.parseMediaType("image/webp")));
        failOnce.set(detailKey);
        assertThatThrownBy(() -> service.recover(detailKey, false)).isInstanceOf(ImageStorageException.class);
        assertThat(objects.get(originalKey).bytes()).isEqualTo(original);
        assertThat(objects.get(thumbnailKey).bytes()).isEqualTo(normalThumbnail);
        assertThat(service.recover(detailKey, false).status()).isEqualTo("RESTORED");
        assertThat(objects.get(thumbnailKey).bytes()).isEqualTo(normalThumbnail);
        assertWebp(objects.get(detailKey), 960);
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void retryAfterMissingThumbnailUsesPreservedDetail() {
        failOnce.set(thumbnailKey);
        assertThatThrownBy(() -> service.recover(detailKey, false)).isInstanceOf(ImageStorageException.class);
        Stored savedDetail = objects.get(detailKey);
        assertThat(savedDetail).isNotNull();
        assertThat(objects.get(originalKey).bytes()).isEqualTo(original);
        assertThat(service.recover(detailKey, false).status()).isEqualTo("RESTORED");
        assertThat(objects.get(detailKey)).isSameAs(savedDetail);
        assertWebp(objects.get(thumbnailKey), 240);
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void responseLostAfterPutIsSafeToRetry() {
        loseResponseOnce.set(originalKey);
        assertThatThrownBy(() -> service.recover(detailKey, false)).isInstanceOf(ImageStorageException.class);
        Stored acceptedOriginal = objects.get(originalKey);
        assertThat(acceptedOriginal.bytes()).isEqualTo(original);
        assertThat(service.recover(detailKey, false).status()).isEqualTo("RESTORED");
        assertThat(objects.get(originalKey)).isSameAs(acceptedOriginal);
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void concurrentWorkersPreserveSameObjects() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.recover(detailKey, false));
            var second = executor.submit(() -> service.recover(detailKey, false));
            assertThat(first.get(30, TimeUnit.SECONDS).status()).isIn("RESTORED", "ALREADY_EXISTS");
            assertThat(second.get(30, TimeUnit.SECONDS).status()).isIn("RESTORED", "ALREADY_EXISTS");
        }
        assertThat(objects).hasSize(3);
        assertThat(objects.get(originalKey).bytes()).isEqualTo(original);
        assertWebp(objects.get(detailKey), 960);
        assertWebp(objects.get(thumbnailKey), 240);
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg", "webp"})
    void restoresRealLegacyOriginalsWithoutChangingFormat(String extension) throws Exception {
        String key = "harudle/generated/diary-images/dev/" + UUID.randomUUID() + "/image." + extension;
        MediaType mime = switch (extension) {
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg" -> MediaType.IMAGE_JPEG;
            default -> MediaType.parseMediaType("image/webp");
        };
        byte[] bytes = extension.equals("webp")
                ? encoder.encode(new GeneratedImage(new ByteArrayResource(original), MediaType.IMAGE_PNG))
                        .get(ImageVariant.DETAIL).resource().getContentAsByteArray()
                : sourceImage(extension, Color.ORANGE);
        backups.clear();
        backups.put(key, new Stored(bytes, mime));
        assertThat(service.recover(key, false).status()).isEqualTo("RESTORED");
        assertThat(objects).containsOnlyKeys(key);
        assertThat(objects.get(key).bytes()).isEqualTo(bytes);
        assertThat(objects.get(key).mime()).isEqualTo(mime);
        assertThat(service.recover(key, false).status()).isEqualTo("ALREADY_EXISTS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpg", "webp"})
    void restoresVariantsFromOtherOriginalFormats(String extension) throws Exception {
        String key = root + "image." + extension;
        MediaType mime = extension.equals("jpg") ? MediaType.IMAGE_JPEG : MediaType.parseMediaType("image/webp");
        byte[] bytes = extension.equals("webp")
                ? encoder.encode(new GeneratedImage(new ByteArrayResource(original), MediaType.IMAGE_PNG))
                        .get(ImageVariant.DETAIL).resource().getContentAsByteArray()
                : sourceImage(extension, Color.ORANGE);
        backups.clear();
        backups.put(key, new Stored(bytes, mime));
        assertThat(service.recover(detailKey, false).originalKey()).isEqualTo(key);
        assertThat(objects.get(key).bytes()).isEqualTo(bytes);
        assertWebp(objects.get(detailKey), 960);
        assertWebp(objects.get(thumbnailKey), 240);
        assertThat(objects).hasSize(3);
    }

    private Stored get(String key) {
        Stored value = objects.get(key);
        if (value == null) {
            throw S3Exception.builder().statusCode(404).awsErrorDetails(
                    AwsErrorDetails.builder().errorCode("NoSuchKey").build()).build();
        }
        return value;
    }

    private void assertWebp(Stored value, int width) {
        assertThat(value.mime().toString()).isEqualTo("image/webp");
        byte[] bytes = value.bytes();
        assertThat(new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("WEBP");
        // cwebp의 VP8 lossy 비트스트림에서 실제 가로 크기를 확인한다.
        assertThat(new String(bytes, 12, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("VP8 ");
        assertThat((bytes[26] & 255) | ((bytes[27] & 63) << 8)).isEqualTo(width);
    }

    private byte[] sourceImage(String format, Color color) throws IOException {
        var image = new BufferedImage(1200, 700, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 1200, 700);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(50, 50, 1100, 10);
        graphics.fillRect(50, 50, 10, 600);
        graphics.dispose();
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format.equals("jpg") ? "jpeg" : format, output)).isTrue();
        return output.toByteArray();
    }

    private record Stored(byte[] bytes, MediaType mime) {
    }
}
