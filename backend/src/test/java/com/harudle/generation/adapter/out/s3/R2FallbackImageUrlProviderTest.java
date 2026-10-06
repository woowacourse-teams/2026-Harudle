package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.exception.ImageBackupNotFoundException;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.ImageStorageException.DiagnosticType;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.BackupObjectMetadata;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;

@ExtendWith(OutputCaptureExtension.class)
class R2FallbackImageUrlProviderTest {
    private static final String ROOT = "harudle/generated/diary-images/dev/550e8400-e29b-41d4-a716-446655440000/";
    private static final String ORIGINAL = ROOT + "image.png";
    private static final String DETAIL = ROOT + "image-960.webp";
    private static final String THUMBNAIL = ROOT + "image-240.webp";
    private static final ImageAccessUrl S3_URL = new ImageAccessUrl(URI.create("https://example.s3.amazonaws.com/image"),
            Instant.parse("2099-01-01T00:00:00Z"));
    private static final ImageAccessUrl R2_URL = new ImageAccessUrl(URI.create("https://example.r2.cloudflarestorage.com/image"),
            Instant.parse("2099-01-01T00:00:00Z"));
    private final ImageUrlProvider primary = mock(ImageUrlProvider.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final BackupObjectStorage backup = mock(BackupObjectStorage.class);
    private R2FallbackImageUrlProvider provider;

    @BeforeEach
    void setUp() {
        provider = new R2FallbackImageUrlProvider(primary, storage, backup, s3("dev"), r2("dev"));
    }

    @Test
    void normalS3OnlyChecksAndSignsS3() {
        when(storage.exists(THUMBNAIL)).thenReturn(true);
        when(primary.createAccessUrl(THUMBNAIL)).thenReturn(S3_URL);
        assertThat(provider.createAccessUrl(THUMBNAIL)).isSameAs(S3_URL);
        verify(storage).exists(THUMBNAIL);
        verify(primary).createAccessUrl(THUMBNAIL);
        verifyNoInteractions(backup);
    }

    @ParameterizedTest
    @CsvSource({"image-960.webp,image.png,image/png", "image-240.webp,image.png,image/png",
            "image-960.webp,image.jpg,image/jpeg", "image-240.webp,image.jpg,image/jpeg",
            "image-960.webp,image.webp,image/webp", "image-240.webp,image.webp,image/webp",
            "image.png,image.png,image/png", "image.jpg,image.jpg,image/jpeg", "image.webp,image.webp,image/webp"})
    void missingS3UsesOriginalKeyAndMime(String displayFilename, String originalFilename, String contentType) {
        String key = ROOT + originalFilename;
        found(key, MediaType.parseMediaType(contentType));
        assertThat(provider.createAccessUrl(ROOT + displayFilename)).isSameAs(R2_URL);
        verify(backup).findMetadata(key);
        verify(backup).createAccessUrl(key);
        verify(backup, never()).download(anyString());
        verify(backup, never()).uploadIfAbsent(anyString(), any());
        verifyNoInteractions(primary);
        verify(storage, never()).restoreIfMissing(anyString(), any());
        verify(storage, never()).delete(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void preservesBothUuidFoldersAndCurrentEnvironment(String environment) {
        String key = "harudle/generated/diary-images/" + environment
                + "/550e8400-e29b-41d4-a716-446655440000/123e4567-e89b-12d3-a456-426614174000/image.jpg";
        provider = new R2FallbackImageUrlProvider(primary, storage, backup, s3(environment), r2(environment));
        found(key, MediaType.IMAGE_JPEG);
        assertThat(provider.createAccessUrl(key)).isSameAs(R2_URL);
        verify(backup).createAccessUrl(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENT_ERROR", "AUTHENTICATION_ERROR", "AUTHORIZATION_ERROR", "PROVIDER_ERROR"})
    void sourceFailureFallsBackOnce(String type) {
        when(storage.exists(DETAIL)).thenThrow(new ImageStorageException("S3 조회 실패", null, DiagnosticType.valueOf(type)));
        found(ORIGINAL, MediaType.IMAGE_PNG);
        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(R2_URL);
        verify(storage).exists(DETAIL);
        verify(backup).findMetadata(ORIGINAL);
        verify(backup).createAccessUrl(ORIGINAL);
        verifyNoInteractions(primary);
    }

    @Test
    void signingFailureAlsoFallsBack() {
        when(storage.exists(DETAIL)).thenReturn(true);
        when(primary.createAccessUrl(DETAIL)).thenThrow(new ImageStorageException("서명 실패", null,
                DiagnosticType.AUTHENTICATION_ERROR));
        found(ORIGINAL, MediaType.IMAGE_PNG);
        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(R2_URL);
        verify(primary).createAccessUrl(DETAIL);
        verify(backup).createAccessUrl(ORIGINAL);
    }

    @Test
    void noBackupFailsAfterOneCandidateSearchWithoutSigningOrReturningToS3() {
        assertThatThrownBy(() -> provider.createAccessUrl(THUMBNAIL)).isInstanceOf(ImageBackupNotFoundException.class);
        verify(backup).findMetadata(ORIGINAL);
        verify(backup).findMetadata(ROOT + "image.jpg");
        verify(backup).findMetadata(ROOT + "image.webp");
        verify(backup, never()).createAccessUrl(anyString());
        verifyNoInteractions(primary);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHENTICATION_ERROR", "AUTHORIZATION_ERROR", "CLIENT_ERROR", "CONFIGURATION_ERROR"})
    void backupErrorIsNotTreatedAsAbsenceAndDoesNotTryNextCandidate(String type) {
        when(backup.findMetadata(ORIGINAL)).thenThrow(new BackupStorageException(
                BackupStorageException.FailureType.valueOf(type), new RuntimeException("provider error")));
        assertThatThrownBy(() -> provider.createAccessUrl(DETAIL)).isInstanceOf(ImageStorageException.class)
                .extracting(exception -> ((ImageStorageException) exception).diagnosticType().name()).isEqualTo(type);
        verify(backup).findMetadata(ORIGINAL);
        verifyNoMoreInteractions(backup);
        verifyNoInteractions(primary);
    }

    @Test
    void backupSigningFailureIsNotRetried() {
        found(ORIGINAL, MediaType.IMAGE_PNG);
        when(backup.createAccessUrl(ORIGINAL)).thenThrow(new BackupStorageException(
                BackupStorageException.FailureType.CLIENT_ERROR, new RuntimeException("timeout")));
        assertThatThrownBy(() -> provider.createAccessUrl(DETAIL)).isInstanceOf(ImageStorageException.class);
        verify(backup).createAccessUrl(ORIGINAL);
        verify(backup, never()).findMetadata(ROOT + "image.jpg");
    }

    @Test
    void requestValidationFailureCannotEscapeToR2() {
        when(storage.exists(DETAIL)).thenThrow(new ImageStorageException("invalid key", null,
                DiagnosticType.REQUEST_VALIDATION_ERROR));
        assertThatThrownBy(() -> provider.createAccessUrl(DETAIL)).isInstanceOf(ImageStorageException.class);
        verifyNoInteractions(backup, primary);
    }

    @ParameterizedTest
    @ValueSource(strings = {"harudle/generated/diary-images/prod/id/image.png",
            "harudle/references/generation/dev/image.png", "harudle/generated/diary-images/dev/../image.png",
            "harudle/generated/diary-images/dev/%2e%2e/image.png", "https://example/image.png"})
    void rejectsInvalidScopeBeforeAnyStorageCall(String key) {
        assertThatThrownBy(() -> provider.createAccessUrl(key)).isInstanceOf(ImageStorageException.class)
                .extracting(exception -> ((ImageStorageException) exception).diagnosticType())
                .isEqualTo(DiagnosticType.REQUEST_VALIDATION_ERROR);
        verifyNoInteractions(storage, primary, backup);
    }

    @Test
    void failsAtStartupForMixedEnvironments() {
        assertThatThrownBy(() -> new R2FallbackImageUrlProvider(primary, storage, backup, s3("dev"), r2("prod")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage, primary, backup);
    }

    @Test
    void neverLogsSignedUrlsOrProviderSecrets(CapturedOutput output) {
        String sensitive = "fake-secret https://example/image?X-Amz-Signature=private-signature";
        when(storage.exists(DETAIL)).thenThrow(new ImageStorageException(sensitive));
        found(ORIGINAL, MediaType.IMAGE_PNG);
        when(backup.createAccessUrl(ORIGINAL)).thenReturn(new ImageAccessUrl(URI.create(
                "https://example.r2.cloudflarestorage.com/image?X-Amz-Signature=private-signature"), Instant.MAX));
        provider.createAccessUrl(DETAIL);
        assertThat(output.getOut()).contains("image_url_selected", "s3Result=ERROR", "result=R2")
                .doesNotContain("fake-secret", "X-Amz-Signature", "private-signature");
    }

    private void found(String key, MediaType mime) {
        when(backup.findMetadata(key)).thenReturn(Optional.of(new BackupObjectMetadata(key, mime, 123, null)));
        when(backup.createAccessUrl(key)).thenReturn(R2_URL);
    }

    private S3StorageProperties s3(String environment) {
        return new S3StorageProperties("test-source", "ap-northeast-2", environment,
                "harudle/generated/diary-images/" + environment, "harudle/references/generation/" + environment,
                DataSize.ofMegabytes(20), Duration.ofMinutes(15));
    }

    private R2StorageProperties r2(String environment) {
        return new R2StorageProperties(true, environment, URI.create("https://example.r2.cloudflarestorage.com"),
                "test-backup", "fake-key", "fake-secret", Duration.ofMinutes(15), DataSize.ofMegabytes(20));
    }
}
