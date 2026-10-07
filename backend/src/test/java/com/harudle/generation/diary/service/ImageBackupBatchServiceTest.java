package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.harudle.generation.config.ImageBackupScheduleProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.repository.ImageBackupTarget;
import com.harudle.generation.diary.service.dto.ImageBackupBatchResult.Status;
import com.harudle.generation.diary.service.dto.ImageBackupResult;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.dto.BackupUploadResult;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;

@ExtendWith(OutputCaptureExtension.class)
class ImageBackupBatchServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-07T15:00:00Z");
    private static final String ROOT = "harudle/generated/diary-images/prod/";
    private final DiaryGenerationRepository generations = mock(DiaryGenerationRepository.class);
    private final ImageBackupService backups = mock(ImageBackupService.class);
    private final Clock clock = mock(Clock.class);
    private ImageBackupBatchService service;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        service = new ImageBackupBatchService(generations, backups,
                new ImageBackupScheduleProperties(true, "0 0 0 * * *", "Asia/Seoul", 2),
                new S3StorageProperties("source", "ap-northeast-2", "prod", ROOT.substring(0, ROOT.length() - 1),
                        "harudle/references/generation/prod", DataSize.ofMegabytes(20), Duration.ofMinutes(15)), clock);
    }

    @Test
    void emptyScopeCompletesWithoutStorageCalls() {
        var result = service.backupAll();
        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(result.processedCount()).isZero();
        verify(generations).findImageBackupTargets(GenerationStatus.SUCCEEDED, NOW, ROOT + "%", PageRequest.of(0, 2));
        verifyNoInteractions(backups);
    }

    @Test
    void cursorContinuesPastFailureAndReportsUploadedExistingMissingAndFailed(CapturedOutput output) {
        ImageBackupTarget first = target(1), second = target(2), third = target(3), fourth = target(4);
        when(generations.findImageBackupTargets(any(), any(), anyString(), any())).thenReturn(List.of(first, second));
        when(generations.findImageBackupTargetsAfter(any(), any(), anyString(), eq(second.completedAt()),
                eq(second.generationId()), any())).thenReturn(List.of(third, fourth));
        when(backups.backup(first.imageObjectKey())).thenReturn(Optional.of(result(BackupUploadResult.UPLOADED)));
        when(backups.backup(second.imageObjectKey())).thenThrow(new BackupStorageException(
                BackupStorageException.FailureType.AUTHORIZATION_ERROR, new RuntimeException("fake-secret")));
        when(backups.backup(third.imageObjectKey())).thenReturn(Optional.empty());
        when(backups.backup(fourth.imageObjectKey())).thenReturn(Optional.of(result(BackupUploadResult.ALREADY_EXISTS)));
        var report = service.backupAll();
        assertThat(report.status()).isEqualTo(Status.PARTIAL_FAILURE);
        assertThat(report.processedCount()).isEqualTo(4);
        assertThat(report.uploadedCount()).isEqualTo(1);
        assertThat(report.existingVerifiedCount()).isEqualTo(1);
        assertThat(report.originalMissingCount()).isEqualTo(1);
        assertThat(report.failedCount()).isEqualTo(1);
        verify(generations).findImageBackupTargetsAfter(GenerationStatus.SUCCEEDED, NOW, ROOT + "%",
                fourth.completedAt(), fourth.generationId(), PageRequest.of(0, 2));
        assertThat(output).contains("status=PARTIAL_FAILURE", "failureType=R2_AUTHORIZATION_ERROR")
                .doesNotContain("fake-secret");
    }

    @Test
    void fixesCutoffEvenWhenClockMovesDuringBackup() {
        ImageBackupTarget first = target(1), second = target(2);
        when(generations.findImageBackupTargets(any(), any(), anyString(), any())).thenReturn(List.of(first, second));
        when(backups.backup(anyString())).thenAnswer(invocation -> {
            when(clock.instant()).thenReturn(NOW.plusSeconds(1000));
            return Optional.empty();
        });
        service.backupAll();
        verify(generations).findImageBackupTargetsAfter(GenerationStatus.SUCCEEDED, NOW, ROOT + "%",
                second.completedAt(), second.generationId(), PageRequest.of(0, 2));
    }

    @Test
    void nextRunStartsFromBeginningAndRetriesFailedTarget() {
        var target = target(1);
        when(generations.findImageBackupTargets(any(), any(), anyString(), any())).thenReturn(List.of(target));
        when(backups.backup(target.imageObjectKey())).thenThrow(new IllegalStateException("temporary failure"))
                .thenReturn(Optional.of(result(BackupUploadResult.UPLOADED)));
        var first = service.backupAll();
        var retry = service.backupAll();
        assertThat(first.status()).isEqualTo(Status.PARTIAL_FAILURE);
        assertThat(retry.status()).isEqualTo(Status.COMPLETED);
        assertThat(retry.uploadedCount()).isEqualTo(1);
        assertThat(retry.runId()).isNotEqualTo(first.runId());
        verify(backups, times(2)).backup(target.imageObjectKey());
    }

    @Test
    void interruptionAfterFirstBackupStopsRemainingTargetsAndRerunVerifiesExistingBackup() {
        ImageBackupTarget first = target(1), second = target(2);
        var attempts = new AtomicInteger();
        when(generations.findImageBackupTargets(any(), any(), anyString(), any())).thenReturn(List.of(first, second));
        when(backups.backup(first.imageObjectKey())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                Thread.currentThread().interrupt();
                return Optional.of(result(BackupUploadResult.UPLOADED));
            }
            return Optional.of(result(BackupUploadResult.ALREADY_EXISTS));
        });
        when(backups.backup(second.imageObjectKey())).thenReturn(Optional.of(result(BackupUploadResult.UPLOADED)));
        try {
            var interrupted = service.backupAll();
            assertThat(interrupted.status()).isEqualTo(Status.INTERRUPTED);
            assertThat(interrupted.processedCount()).isEqualTo(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(backups, never()).backup(second.imageObjectKey());
        } finally {
            Thread.interrupted();
        }
        var retry = service.backupAll();
        assertThat(retry.status()).isEqualTo(Status.COMPLETED);
        assertThat(retry.processedCount()).isEqualTo(2);
        assertThat(retry.existingVerifiedCount()).isEqualTo(1);
        assertThat(retry.uploadedCount()).isEqualTo(1);
    }

    @Test
    void alreadyInterruptedExecutionDoesNotQueryOrWrite() {
        Thread.currentThread().interrupt();
        try {
            assertThat(service.backupAll().status()).isEqualTo(Status.INTERRUPTED);
            verifyNoInteractions(generations, backups);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shutdownRequestBeforeExecutionDoesNotQueryOrWrite() {
        service.requestStop();

        var report = service.backupAll();

        assertThat(report.status()).isEqualTo(Status.INTERRUPTED);
        assertThat(report.processedCount()).isZero();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        verifyNoInteractions(generations, backups);
    }

    @Test
    void shutdownRequestDuringFirstBackupFinishesItWithoutStartingNextTargetOrPage(CapturedOutput output) {
        ImageBackupTarget first = target(1), second = target(2);
        when(generations.findImageBackupTargets(any(), any(), anyString(), any())).thenReturn(List.of(first, second));
        when(backups.backup(first.imageObjectKey())).thenAnswer(invocation -> {
            service.requestStop();
            return Optional.of(result(BackupUploadResult.UPLOADED));
        });

        var report = service.backupAll();

        assertThat(report.status()).isEqualTo(Status.INTERRUPTED);
        assertThat(report.processedCount()).isEqualTo(1);
        assertThat(report.uploadedCount()).isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        verify(backups, never()).backup(second.imageObjectKey());
        verify(generations, never()).findImageBackupTargetsAfter(any(), any(), anyString(), any(), any(), any());
        assertThat(output).contains("status=INTERRUPTED", "processedCount=1", "uploadedCount=1");
    }

    @Test
    void shutdownRequestDuringPageQueryDoesNotStartFirstTarget() {
        when(generations.findImageBackupTargets(any(), any(), anyString(), any())).thenAnswer(invocation -> {
            service.requestStop();
            return List.of(target(1), target(2));
        });

        var report = service.backupAll();

        assertThat(report.status()).isEqualTo(Status.INTERRUPTED);
        assertThat(report.processedCount()).isZero();
        verifyNoInteractions(backups);
        verify(generations, never()).findImageBackupTargetsAfter(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void queryFailureAbortsBatchAndDoesNotLeakExceptionMessage(CapturedOutput output) {
        when(generations.findImageBackupTargets(any(), any(), anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("fake-secret"));
        assertThatThrownBy(service::backupAll).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(backups);
        assertThat(output).contains("status=FAILED").doesNotContain("fake-secret");
    }

    private ImageBackupTarget target(int value) {
        UUID id = new UUID(0, value);
        return new ImageBackupTarget(id, NOW.minusSeconds(1), ROOT + id + "/image-960.webp");
    }

    private ImageBackupResult result(BackupUploadResult status) {
        return new ImageBackupResult(ROOT + "image.png", status, MediaType.IMAGE_PNG, 10, "a".repeat(64), NOW);
    }
}
