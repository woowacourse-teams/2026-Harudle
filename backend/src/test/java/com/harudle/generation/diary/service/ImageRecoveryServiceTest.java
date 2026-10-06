package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.exception.ImageRecoveryException;
import com.harudle.generation.diary.service.port.*;
import com.harudle.generation.diary.service.port.dto.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ImageRecoveryServiceTest {
    private static final String ROOT = "harudle/generated/diary-images/dev/" + UUID.randomUUID() + "/";
    private static final String PNG = ROOT + "image.png";
    private static final String DETAIL = ROOT + "image-960.webp";
    private static final String THUMBNAIL = ROOT + "image-240.webp";
    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jKp8AAAAASUVORK5CYII=");
    private final ImageStorage storage = mock(ImageStorage.class);
    private final BackupObjectStorage backup = mock(BackupObjectStorage.class);
    private ImageRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new ImageRecoveryService(storage, backup, s3(), r2("dev"), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        when(backup.findMetadata(PNG)).thenReturn(Optional.of(
                new BackupObjectMetadata(PNG, MediaType.IMAGE_PNG, PNG_BYTES.length, "opaque-etag")));
        when(backup.download(PNG)).thenReturn(Optional.of(image(PNG_BYTES, MediaType.IMAGE_PNG)));
    }

    @Test
    void dryRunShowsMappingAndMissingKeysWithoutWrites() {
        var result = service.recover(DETAIL, true);
        assertThat(result.status()).isEqualTo("WOULD_RESTORE");
        assertThat(result.originalKey()).isEqualTo(PNG);
        assertThat(result.missingKeys()).containsExactly(PNG, DETAIL, THUMBNAIL);
        assertThat(result.sha256()).matches("[0-9a-f]{64}");
        assertThat(result.mime()).isEqualTo("image/png");
        assertThat(result.originalRestored()).isFalse();
        verify(storage, never()).restoreIfMissing(anyString(), any());
        verify(storage, never()).restoreOptimizedIfMissing(anyString(), any());
        verify(storage, never()).delete(anyString());
        verify(backup, never()).uploadIfAbsent(anyString(), any());
    }

    @Test
    void restoresOriginalAndVariantsAndVerifiesDestination() {
        when(storage.exists(anyString())).thenReturn(false, false, false, true, true, true);
        when(storage.restoreIfMissing(eq(PNG), any())).thenReturn(true);
        when(storage.load(PNG)).thenReturn(image(PNG_BYTES, MediaType.IMAGE_PNG));
        when(storage.restoreOptimizedIfMissing(eq(DETAIL), any())).thenReturn(true);
        var result = service.recover(DETAIL, false);
        assertThat(result.status()).isEqualTo("RESTORED");
        assertThat(result.originalRestored()).isTrue();
        assertThat(result.variantsRestored()).isTrue();
        var order = inOrder(storage);
        order.verify(storage).exists(PNG);
        order.verify(storage).exists(DETAIL);
        order.verify(storage).exists(THUMBNAIL);
        order.verify(storage).restoreIfMissing(eq(PNG), any());
        order.verify(storage).load(PNG);
        order.verify(storage).restoreOptimizedIfMissing(eq(DETAIL), any());
        verify(storage, never()).store(any(), any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void skipsVerifiedExistingObjects() {
        when(storage.exists(anyString())).thenReturn(true);
        when(storage.load(PNG)).thenReturn(image(PNG_BYTES, MediaType.IMAGE_PNG));
        assertThat(service.recover(DETAIL, false).status()).isEqualTo("ALREADY_EXISTS");
        verify(storage, never()).restoreIfMissing(anyString(), any());
        verify(storage, never()).restoreOptimizedIfMissing(anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failsWhenBackupMissingWithoutCallingS3OrAi(boolean dryRun) {
        when(backup.findMetadata(PNG)).thenReturn(Optional.empty());
        assertFailure(() -> service.recover(DETAIL, dryRun), "BACKUP_NOT_FOUND");
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsAmbiguousOriginalMappingBeforeWrites() {
        String jpg = ROOT + "image.jpg";
        when(backup.findMetadata(jpg)).thenReturn(Optional.of(
                new BackupObjectMetadata(jpg, MediaType.IMAGE_JPEG, 3, null)));
        assertFailure(() -> service.recover(DETAIL, false), "AMBIGUOUS_BACKUP");
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsChangedBackupMetadata() {
        when(backup.findMetadata(PNG)).thenReturn(Optional.of(
                new BackupObjectMetadata(PNG, MediaType.IMAGE_PNG, PNG_BYTES.length + 1, null)));
        assertFailure(() -> service.recover(DETAIL, false), "BACKUP_CHANGED");
        verifyNoInteractions(storage);
    }

    @Test
    void stillRejectsBackupMetadataWithDifferentMimeParameters() {
        MediaType mime = MediaType.parseMediaType("image/png;charset=UTF-8");
        when(backup.findMetadata(PNG)).thenReturn(Optional.of(
                new BackupObjectMetadata(PNG, mime, PNG_BYTES.length, null)));
        assertFailure(() -> service.recover(PNG, false), "BACKUP_CHANGED");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesExistingOriginalWhenOnlyMimeParametersDiffer(boolean dryRun) {
        MediaType mime = MediaType.parseMediaType("image/png;charset=UTF-8");
        when(backup.findMetadata(PNG)).thenReturn(Optional.of(
                new BackupObjectMetadata(PNG, mime, PNG_BYTES.length, null)));
        when(backup.download(PNG)).thenReturn(Optional.of(image(PNG_BYTES, mime)));
        when(storage.exists(PNG)).thenReturn(true);
        when(storage.load(PNG)).thenReturn(image(PNG_BYTES, MediaType.IMAGE_PNG));
        assertFailure(() -> service.recover(PNG, dryRun), "ORIGINAL_CONFLICT");
        verify(storage, never()).restoreIfMissing(anyString(), any());
        verify(storage, never()).delete(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg;charset=UTF-8", "image/svg+xml", "image/gif"})
    void rejectsMismatchedMimeBeforeDestinationAccess(String contentType) {
        MediaType mime = MediaType.parseMediaType(contentType);
        ReferenceImage original = image(PNG_BYTES, mime);
        when(backup.findMetadata(PNG)).thenReturn(Optional.of(
                new BackupObjectMetadata(PNG, mime, PNG_BYTES.length, null)));
        when(backup.download(PNG)).thenReturn(Optional.of(original));
        assertFailure(() -> service.recover(PNG, false), "INVALID_CONTENT");
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/*", "*/*", "image/*+xml"})
    void rejectsWildcardBackupMetadataBeforeDestinationAccess(String contentType) {
        when(backup.findMetadata(PNG)).thenReturn(Optional.of(new BackupObjectMetadata(
                PNG, MediaType.parseMediaType(contentType), PNG_BYTES.length, null)));
        assertFailure(() -> service.recover(PNG, false), "BACKUP_CHANGED");
        verifyNoInteractions(storage);
    }

    @Test
    void rejectsNonImageBytesEvenWithPngMime() {
        byte[] bytes = new byte[PNG_BYTES.length];
        when(backup.download(PNG)).thenReturn(Optional.of(image(bytes, MediaType.IMAGE_PNG)));
        assertFailure(() -> service.recover(PNG, false), "INVALID_CONTENT");
        verifyNoInteractions(storage);
    }

    @Test
    void stopsWhenAnyDestinationStateCannotBeRead() {
        when(storage.exists(THUMBNAIL)).thenThrow(new ImageStorageException("access denied"));
        assertThatThrownBy(() -> service.recover(DETAIL, false)).isInstanceOf(ImageStorageException.class);
        verify(storage, never()).restoreIfMissing(anyString(), any());
        verify(storage, never()).restoreOptimizedIfMissing(anyString(), any());
    }

    @Test
    void propagatesR2PermissionFailureInsteadOfTreatingItAsMissing() {
        when(backup.findMetadata(PNG)).thenThrow(new BackupStorageException(
                BackupStorageException.FailureType.AUTHORIZATION_ERROR, new RuntimeException("denied")));
        assertThatThrownBy(() -> service.recover(DETAIL, true)).isInstanceOf(BackupStorageException.class);
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preservesConflictingExistingOriginal(boolean dryRun) {
        when(storage.exists(PNG)).thenReturn(true);
        when(storage.load(PNG)).thenReturn(image(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));
        assertFailure(() -> service.recover(DETAIL, dryRun), "ORIGINAL_CONFLICT");
        verify(storage, never()).restoreIfMissing(anyString(), any());
        verify(storage, never()).restoreOptimizedIfMissing(anyString(), any());
    }

    @Test
    void detectsConcurrentDifferentOriginalBeforeCreatingVariants() {
        when(storage.restoreIfMissing(eq(PNG), any())).thenReturn(false);
        when(storage.load(PNG)).thenReturn(image(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));
        assertFailure(() -> service.recover(DETAIL, false), "ORIGINAL_CONFLICT");
        verify(storage, never()).restoreOptimizedIfMissing(anyString(), any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void reportsPartialFailureAndPreservesRestoredOriginal() {
        when(storage.restoreIfMissing(eq(PNG), any())).thenReturn(true);
        when(storage.load(PNG)).thenReturn(image(PNG_BYTES, MediaType.IMAGE_PNG));
        when(storage.restoreOptimizedIfMissing(eq(DETAIL), any())).thenThrow(new ImageStorageException("timeout"));
        assertThatThrownBy(() -> service.recover(DETAIL, false)).isInstanceOf(ImageStorageException.class);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void failsVerificationIfVariantStillMissing() {
        when(storage.exists(PNG)).thenReturn(true);
        when(storage.load(PNG)).thenReturn(image(PNG_BYTES, MediaType.IMAGE_PNG));
        when(storage.restoreOptimizedIfMissing(eq(DETAIL), any())).thenReturn(true);
        assertFailure(() -> service.recover(DETAIL, false), "VERIFICATION_FAILED");
        verify(storage, never()).delete(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"image.jpg", "image.webp", "image.png"})
    void supportsLegacySingleObjectAndOriginalWebp(String filename) {
        String key = ROOT + filename;
        MediaType mime = switch (filename) {
            case "image.jpg" -> MediaType.IMAGE_JPEG;
            case "image.webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.IMAGE_PNG;
        };
        byte[] bytes = switch (filename) {
            case "image.jpg" -> new byte[]{(byte) 255, (byte) 216, (byte) 255, (byte) 217};
            case "image.webp" -> "RIFF1234WEBPpayload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            default -> PNG_BYTES;
        };
        when(backup.findMetadata(key)).thenReturn(Optional.of(new BackupObjectMetadata(key, mime, bytes.length, null)));
        when(backup.download(key)).thenReturn(Optional.of(image(bytes, mime)));
        when(storage.exists(key)).thenReturn(false, true);
        when(storage.load(key)).thenReturn(image(bytes, mime));
        when(storage.restoreIfMissing(eq(key), any())).thenReturn(true);
        var result = service.recover(key, false);
        assertThat(result.status()).isEqualTo("RESTORED");
        assertThat(result.originalKey()).isEqualTo(key);
        verify(storage, never()).restoreOptimizedIfMissing(anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"harudle/generated/diary-images/prod/id/image.png",
            "harudle/references/generation/dev/image.png", "harudle/generated/diary-images/dev/../image.png",
            "harudle/generated/diary-images/dev/id/image-240.webp", "https://example/image.png"})
    void rejectsWrongScopeBeforeStorageAccess(String key) {
        clearInvocations(backup);
        assertThatThrownBy(() -> service.recover(key, false)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage, backup);
    }

    @Test
    void rejectsDifferentConfiguredEnvironments() {
        assertThatThrownBy(() -> new ImageRecoveryService(storage, backup, s3(), r2("prod"), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsFailureWithoutSecretsOrSignedUrls(CapturedOutput output) {
        when(backup.findMetadata(PNG)).thenThrow(new BackupStorageException(
                BackupStorageException.FailureType.AUTHENTICATION_ERROR,
                new RuntimeException("fake-secret https://example/image.png?X-Amz-Signature=private-signature")));
        assertThatThrownBy(() -> service.recover(DETAIL, false)).isInstanceOf(BackupStorageException.class);
        assertThat(output.getOut()).contains("image_r2_recovery_completed", "READ_BACKUP", "FAILED", "R2_AUTHENTICATION_ERROR")
                .doesNotContain("fake-secret", "X-Amz-Signature", "private-signature");
    }

    private static ReferenceImage image(byte[] bytes, MediaType mime) {
        return new ReferenceImage(new ByteArrayResource(bytes), mime);
    }

    private static S3StorageProperties s3() {
        return new S3StorageProperties("test-source", "ap-northeast-2", "dev",
                "harudle/generated/diary-images/dev", "harudle/references/generation/dev",
                DataSize.ofMegabytes(20), Duration.ofMinutes(15));
    }

    private static R2StorageProperties r2(String environment) {
        return new R2StorageProperties(true, environment, URI.create("https://example.r2.cloudflarestorage.com"),
                "test-backup", "fake-key", "fake-secret", Duration.ofMinutes(15), DataSize.ofMegabytes(20), Duration.ofSeconds(2));
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String reason) {
        assertThatThrownBy(call).isInstanceOf(ImageRecoveryException.class)
                .extracting(exception -> ((ImageRecoveryException) exception).reason().name()).isEqualTo(reason);
    }
}
