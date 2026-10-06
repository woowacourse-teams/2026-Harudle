package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.dto.ImageBackupResult;
import com.harudle.generation.diary.service.exception.ImageBackupException;
import com.harudle.generation.diary.service.exception.ImageBackupException.Reason;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.BackupStorageException.FailureType;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.ImageStorageException.DiagnosticType;
import com.harudle.generation.diary.service.port.dto.BackupUploadResult;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;

class ImageBackupServiceTest {

    private static final String FOLDER = "harudle/generated/diary-images/dev/diary-id/";
    private static final String KEY = FOLDER + "image.png";
    private static final String DETAIL_KEY = FOLDER + "image-960.webp";
    private static final byte[] BYTES = "abc".getBytes(StandardCharsets.UTF_8);
    private static final String SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final Instant VERIFIED_AT = Instant.parse("2026-10-02T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(VERIFIED_AT, ZoneOffset.UTC);
    private ImageStorage source;
    private BackupObjectStorage backup;
    private ImageBackupService service;

    @BeforeEach
    void setUp() {
        source = mock(ImageStorage.class);
        backup = mock(BackupObjectStorage.class);
        service = new ImageBackupService(source, backup, sourceProperties(), backupProperties("dev", 8), CLOCK);
    }

    @ParameterizedTest
    @CsvSource({"image.png,image/png", "image.jpg,image/jpeg", "image.webp,image/webp"})
    @DisplayName("대표 키에서 원본을 선택해 동일한 키와 바이트로 업로드하고 실제 SHA-256을 검증한다")
    void backupOriginalAndVerifyContent(String filename, String mime) throws IOException {
        String key = FOLDER + filename;
        prepareSource(key, BYTES, mime);
        prepareBackup(key, BYTES, mime, BackupUploadResult.UPLOADED);

        ImageBackupResult result = service.backup(DETAIL_KEY).orElseThrow();

        assertThat(result).isEqualTo(new ImageBackupResult(key, BackupUploadResult.UPLOADED,
                MediaType.parseMediaType(mime), BYTES.length, SHA256, VERIFIED_AT));
        ArgumentCaptor<GeneratedImage> uploaded = ArgumentCaptor.forClass(GeneratedImage.class);
        verify(backup).uploadIfAbsent(eq(key), uploaded.capture());
        assertThat(uploaded.getValue().resource().getContentAsByteArray()).containsExactly(BYTES);
        assertThat(uploaded.getValue().mediaType().toString()).isEqualTo(mime);
        verify(backup).download(key);
        verifyNoMoreInteractions(backup);
        verify(source, never()).exists(DETAIL_KEY);
        verify(source, never()).load(DETAIL_KEY);
        verify(source, never()).store(any(), any());
        verify(source, never()).delete(any());
    }

    @Test
    @DisplayName("후보를 PNG, JPG, WebP 순서로 확인하고 첫 번째 원본을 사용한다")
    void selectFirstExistingOriginal() {
        String jpegKey = FOLDER + "image.jpg";
        prepareSource(jpegKey, BYTES, "image/jpeg");
        prepareBackup(jpegKey, BYTES, "image/jpeg", BackupUploadResult.UPLOADED);

        service.backup(DETAIL_KEY);

        var order = inOrder(source, backup);
        order.verify(source).exists(KEY);
        order.verify(source).exists(jpegKey);
        order.verify(source).load(jpegKey);
        order.verify(backup).uploadIfAbsent(eq(jpegKey), any());
        order.verify(backup).download(jpegKey);
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("원본 키가 입력되면 다른 확장자 후보를 조회하지 않는다")
    void useOriginalKeyDirectly() {
        prepareSource(KEY, BYTES, "image/png");
        prepareBackup(KEY, BYTES, "image/png", BackupUploadResult.UPLOADED);

        assertThat(service.backup(KEY)).isPresent();
        verify(source).exists(KEY);
        verify(source).load(KEY);
        verifyNoMoreInteractions(source);
    }

    @Test
    @DisplayName("이미 존재하는 R2 백업도 다시 다운로드하여 검증한다")
    void verifyExistingBackup() {
        prepareSource(KEY, BYTES, "image/png");
        prepareBackup(KEY, BYTES, "image/png", BackupUploadResult.ALREADY_EXISTS);

        ImageBackupResult result = service.backup(DETAIL_KEY).orElseThrow();

        assertThat(result.uploadResult()).isEqualTo(BackupUploadResult.ALREADY_EXISTS);
        assertThat(result.sha256()).isEqualTo(SHA256);
        verify(backup).download(KEY);
    }

    @Test
    @DisplayName("원본 후보가 모두 없으면 결과 없음으로 반환하고 R2에는 요청하지 않는다")
    void reportMissingOriginal() {
        assertThat(service.backup(DETAIL_KEY)).isEmpty();
        verify(source).exists(KEY);
        verify(source).exists(FOLDER + "image.jpg");
        verify(source).exists(FOLDER + "image.webp");
        verifyNoMoreInteractions(source);
        verifyNoInteractions(backup);
    }

    @ParameterizedTest
    @EnumSource(BackupUploadResult.class)
    @DisplayName("업로드 결과가 신규 또는 기존이어도 같은 크기의 다른 바이트를 성공으로 취급하지 않는다")
    void rejectSha256Mismatch(BackupUploadResult uploadResult) {
        prepareSource(KEY, BYTES, "image/png");
        prepareBackup(KEY, "abd".getBytes(StandardCharsets.UTF_8), "image/png", uploadResult);

        assertBackupFailure(() -> service.backup(DETAIL_KEY), Reason.VERIFICATION_FAILED);
        verify(backup).uploadIfAbsent(eq(KEY), any());
        verify(backup).download(KEY);
        verifyNoMoreInteractions(backup);
        verify(source, never()).delete(any());
    }

    @ParameterizedTest
    @CsvSource({"abcd,image/png", "abc,image/webp", "abc,image/png;charset=UTF-8"})
    @DisplayName("R2 원본의 크기나 MIME이 다르면 검증 실패로 처리한다")
    void rejectSizeOrMimeMismatch(String content, String mime) {
        prepareSource(KEY, BYTES, "image/png");
        prepareBackup(KEY, content.getBytes(StandardCharsets.UTF_8), mime, BackupUploadResult.UPLOADED);

        assertBackupFailure(() -> service.backup(DETAIL_KEY), Reason.VERIFICATION_FAILED);
        verify(source, never()).delete(any());
    }

    @Test
    @DisplayName("업로드 응답 후 R2 파일이 없으면 성공으로 반환하지 않는다")
    void rejectMissingBackupAfterUpload() {
        prepareSource(KEY, BYTES, "image/png");
        when(backup.uploadIfAbsent(eq(KEY), any())).thenReturn(BackupUploadResult.UPLOADED);
        when(backup.download(KEY)).thenReturn(Optional.empty());

        assertBackupFailure(() -> service.backup(DETAIL_KEY), Reason.BACKUP_NOT_FOUND);
        verify(source, never()).delete(any());
    }

    @Test
    @DisplayName("S3 후보 조회 권한 오류를 원본 없음으로 숨기지 않는다")
    void propagateSourceLookupFailure() {
        ImageStorageException error = new ImageStorageException("조회 실패", null, DiagnosticType.AUTHORIZATION_ERROR);
        when(source.exists(KEY)).thenThrow(error);

        assertThatThrownBy(() -> service.backup(DETAIL_KEY)).isSameAs(error);
        verifyNoInteractions(backup);
    }

    @Test
    @DisplayName("S3 조회 도중 원본이 사라지거나 통신이 실패하면 R2 업로드를 시작하지 않는다")
    void propagateSourceReadFailure() {
        when(source.exists(KEY)).thenReturn(true);
        ImageStorageException error = new ImageStorageException("조회 실패", null, DiagnosticType.CLIENT_ERROR);
        when(source.load(KEY)).thenThrow(error);

        assertThatThrownBy(() -> service.backup(DETAIL_KEY)).isSameAs(error);
        verifyNoInteractions(backup);
    }

    @ParameterizedTest
    @EnumSource(value = FailureType.class, names = {"AUTHORIZATION_ERROR", "AUTHENTICATION_ERROR", "CLIENT_ERROR"})
    @DisplayName("R2 업로드 오류를 전달하며 다운로드나 삭제를 진행하지 않는다")
    void propagateUploadFailure(FailureType type) {
        prepareSource(KEY, BYTES, "image/png");
        BackupStorageException error = new BackupStorageException(type, new IOException("private-error-message"));
        when(backup.uploadIfAbsent(eq(KEY), any())).thenThrow(error);

        assertThatThrownBy(() -> service.backup(DETAIL_KEY)).isSameAs(error);
        verify(backup).uploadIfAbsent(eq(KEY), any());
        verifyNoMoreInteractions(backup);
        verify(source, never()).delete(any());
    }

    @Test
    @DisplayName("업로드 이후 다운로드가 실패하면 원본을 보존하고 예외를 전달한다")
    void preserveOriginalOnBackupReadFailure() {
        prepareSource(KEY, BYTES, "image/png");
        when(backup.uploadIfAbsent(eq(KEY), any())).thenReturn(BackupUploadResult.UPLOADED);
        BackupStorageException error = new BackupStorageException(FailureType.CLIENT_ERROR, new IOException("timeout"));
        when(backup.download(KEY)).thenThrow(error);

        assertThatThrownBy(() -> service.backup(DETAIL_KEY)).isSameAs(error);
        verify(source, never()).delete(any());
        verify(backup).uploadIfAbsent(eq(KEY), any());
        verify(backup).download(KEY);
        verifyNoMoreInteractions(backup);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "harudle/generated/diary-images/prod/diary-id/image.png",
            "harudle/references/generation/dev/diary-id/image.png",
            "harudle/generated/diary-images/dev/../prod/image.png",
            "harudle/generated/diary-images/dev/%2e%2e/image.png",
            "harudle/generated/diary-images/dev//image.png",
            "harudle/generated/diary-images/dev/diary-id\\image.png",
            "harudle/generated/diary-images/dev/diary-id\n/image.png",
            "harudle/generated/diary-images/dev/diary-id/image-240.webp"
    })
    @DisplayName("다른 환경과 잘못된 키는 저장소 조회 전에 거절한다")
    void rejectInvalidKey(String key) {
        assertThatThrownBy(() -> service.backup(key)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(source, backup);
    }

    @Test
    @DisplayName("너무 긴 UTF-8 키는 저장소 조회 전에 거절한다")
    void rejectOversizedKey() {
        assertThatThrownBy(() -> service.backup(FOLDER + "가".repeat(350) + "/image.png"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(source, backup);
    }

    @Test
    @DisplayName("원본 실제 크기가 제한을 넘으면 업로드 전에 중단한다")
    void boundSourceBytes() {
        prepareSource(KEY, new byte[9], "image/png");
        assertBackupFailure(() -> service.backup(DETAIL_KEY), Reason.INVALID_CONTENT_SIZE);
        verifyNoInteractions(backup);
    }

    @Test
    @DisplayName("원본 스트림을 닫는 중 실패해도 업로드를 시작하지 않는다")
    void failBeforeUploadWhenSourceStreamCannotClose() {
        when(source.exists(KEY)).thenReturn(true);
        ByteArrayResource resource = new ByteArrayResource(BYTES) {
            @Override
            public ByteArrayInputStream getInputStream() {
                return new ByteArrayInputStream(BYTES) {
                    @Override
                    public void close() throws IOException {
                        throw new IOException("private stream failure");
                    }
                };
            }
        };
        when(source.load(KEY)).thenReturn(new ReferenceImage(resource, MediaType.IMAGE_PNG));

        assertBackupFailure(() -> service.backup(DETAIL_KEY), Reason.CONTENT_READ_FAILED);
        verifyNoInteractions(backup);
    }

    @Test
    @DisplayName("R2 원본 실제 크기도 제한한다")
    void boundBackupBytes() {
        prepareSource(KEY, BYTES, "image/png");
        prepareBackup(KEY, new byte[9], "image/png", BackupUploadResult.UPLOADED);
        assertBackupFailure(() -> service.backup(DETAIL_KEY), Reason.INVALID_CONTENT_SIZE);
        verify(source, never()).delete(any());
    }

    @Test
    @DisplayName("S3보다 작은 R2 크기 제한을 적용한다")
    void useSmallerStorageLimit() {
        service = new ImageBackupService(source, backup, sourceProperties(), backupProperties("dev", 2), CLOCK);
        prepareSource(KEY, BYTES, "image/png");
        assertBackupFailure(() -> service.backup(DETAIL_KEY), Reason.INVALID_CONTENT_SIZE);
        verifyNoInteractions(backup);
    }

    @Test
    @DisplayName("S3와 R2 환경이 다르면 서비스를 구성하지 않는다")
    void rejectMismatchedEnvironments() {
        assertThatThrownBy(() -> new ImageBackupService(source, backup,
                sourceProperties(), backupProperties("prod", 8), CLOCK))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("실행 환경이 일치");
        verifyNoInteractions(source, backup);
    }

    @Test
    @DisplayName("검증 완료 로그에 버킷, 키, 양쪽 SHA-256과 검증 시각을 기록한다")
    void auditVerifiedBackup() {
        prepareSource(KEY, BYTES, "image/png");
        prepareBackup(KEY, BYTES, "image/png", BackupUploadResult.ALREADY_EXISTS);
        ILoggingEvent event = captureLog(() -> service.backup(DETAIL_KEY));

        assertThat(fields(event)).containsAllEntriesOf(Map.of(
                "result", "VERIFIED", "uploadResult", BackupUploadResult.ALREADY_EXISTS,
                "representativeKey", DETAIL_KEY, "originalKey", KEY, "r2Key", KEY,
                "s3Bucket", "test-source", "r2Bucket", "test-backup",
                "sourceSha256", SHA256, "backupSha256", SHA256, "verifiedAt", VERIFIED_AT
        ));
        assertThat(fields(event)).containsAllEntriesOf(Map.of(
                "mimeMatches", true, "sizeMatches", true, "sha256Matches", true,
                "sourceSize", 3L, "backupSize", 3L, "failureType", "none"
        ));
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("원본 없음을 검증 성공과 별도로 로그에 기록한다")
    void auditMissingOriginal() {
        ILoggingEvent event = captureLog(() -> assertThat(service.backup(DETAIL_KEY)).isEmpty());
        assertThat(fields(event)).containsEntry("result", "ORIGINAL_NOT_FOUND")
                .containsEntry("verifiedAt", "none").containsEntry("uploadResult", "none");
    }

    @Test
    @DisplayName("내용 불일치를 성공으로 로그에 남기지 않는다")
    void auditVerificationFailure() {
        prepareSource(KEY, BYTES, "image/png");
        prepareBackup(KEY, "abd".getBytes(StandardCharsets.UTF_8), "image/png", BackupUploadResult.ALREADY_EXISTS);
        ILoggingEvent event = captureLog(() ->
                assertBackupFailure(() -> service.backup(DETAIL_KEY), Reason.VERIFICATION_FAILED));

        assertThat(fields(event)).containsEntry("result", "FAILED")
                .containsEntry("stage", "VERIFY_BACKUP")
                .containsEntry("sha256Matches", false)
                .containsEntry("failureType", "VERIFICATION_FAILED")
                .containsEntry("verifiedAt", "none");
    }

    @Test
    @DisplayName("오류 메시지, 서명 URL과 자격 증명을 로그에 노출하지 않는다")
    void auditProviderFailureWithoutSecrets() {
        prepareSource(KEY, BYTES, "image/png");
        BackupStorageException error = new BackupStorageException(FailureType.AUTHORIZATION_ERROR,
                new IOException("test-private-key https://example.com/image?X-Amz-Signature=secret"));
        when(backup.uploadIfAbsent(eq(KEY), any())).thenThrow(error);
        ILoggingEvent event = captureLog(() -> assertThatThrownBy(() -> service.backup(DETAIL_KEY)).isSameAs(error));

        assertThat(fields(event)).containsEntry("result", "FAILED")
                .containsEntry("failureType", "AUTHORIZATION_ERROR").containsEntry("stage", "UPLOAD_BACKUP");
        assertThat(event.getFormattedMessage() + fields(event)).doesNotContain("test-private-key", "https://", "X-Amz-");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("검증되지 않은 키는 로그에 원문을 남기지 않는다")
    void redactInvalidKeyInLog() {
        String invalid = FOLDER + "secret\n/image.png";
        ILoggingEvent event = captureLog(() ->
                assertThatThrownBy(() -> service.backup(invalid)).isInstanceOf(IllegalArgumentException.class));

        assertThat(fields(event)).containsEntry("representativeKey", "invalid")
                .containsEntry("failureType", "REQUEST_VALIDATION_ERROR");
        assertThat(event.getFormattedMessage() + fields(event)).doesNotContain(invalid, "secret");
    }

    private void prepareSource(String key, byte[] bytes, String mime) {
        when(source.exists(key)).thenReturn(true);
        when(source.load(key)).thenReturn(image(bytes, mime));
    }

    private void prepareBackup(String key, byte[] bytes, String mime, BackupUploadResult uploadResult) {
        when(backup.uploadIfAbsent(eq(key), any())).thenReturn(uploadResult);
        when(backup.download(key)).thenReturn(Optional.of(image(bytes, mime)));
    }

    private static ReferenceImage image(byte[] bytes, String mime) {
        return new ReferenceImage(new ByteArrayResource(bytes), MediaType.parseMediaType(mime));
    }

    private static void assertBackupFailure(Runnable action, Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ImageBackupException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private static ILoggingEvent captureLog(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ImageBackupService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        assertThat(appender.list).hasSize(1);
        return appender.list.getFirst();
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    private static S3StorageProperties sourceProperties() {
        return new S3StorageProperties("test-source", "ap-northeast-2", "dev",
                "harudle/generated/diary-images/dev", "harudle/references/generation/dev",
                DataSize.ofBytes(8), Duration.ofMinutes(15));
    }

    private static R2StorageProperties backupProperties(String environment, int maxSize) {
        return new R2StorageProperties(true, environment, URI.create("https://example.r2.cloudflarestorage.com"),
                "test-backup", "test-key", "test-secret", Duration.ofMinutes(15), DataSize.ofBytes(maxSize), Duration.ofSeconds(2));
    }
}
