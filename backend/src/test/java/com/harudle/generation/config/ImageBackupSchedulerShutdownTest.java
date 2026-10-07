package com.harudle.generation.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.repository.ImageBackupTarget;
import com.harudle.generation.diary.service.ImageBackupScheduler;
import com.harudle.generation.diary.service.ImageBackupService;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.dto.BackupUploadResult;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.unit.DataSize;

@ExtendWith(OutputCaptureExtension.class)
class ImageBackupSchedulerShutdownTest {
    private static final Instant NOW = Instant.parse("2026-10-07T15:00:00Z");
    private static final String ROOT = "harudle/generated/diary-images/prod/";

    @Test
    void contextCloseFinishesCurrentOriginalWithoutStartingNextAndReleasesLock(CapturedOutput output)
            throws Exception {
        var generations = mock(DiaryGenerationRepository.class);
        var source = mock(ImageStorage.class);
        var backupStorage = mock(BackupObjectStorage.class);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBoolean(1)).thenReturn(true);

        UUID firstId = new UUID(0, 1), secondId = new UUID(0, 2);
        String firstKey = ROOT + firstId + "/image.png";
        String secondKey = ROOT + secondId + "/image.png";
        when(generations.findImageBackupTargets(any(), any(), anyString(), any())).thenReturn(List.of(
                new ImageBackupTarget(firstId, NOW.minusSeconds(1), firstKey),
                new ImageBackupTarget(secondId, NOW.minusSeconds(1), secondKey)));
        when(source.exists(anyString())).thenReturn(true);

        var firstReadStarted = new CountDownLatch(1);
        var releaseFirstRead = new CountDownLatch(1);
        var lifecycleStopStarted = new CountDownLatch(1);
        var backupThread = new AtomicReference<Thread>();
        byte[] original = "first-original".getBytes(UTF_8);
        when(source.load(firstKey)).thenAnswer(invocation -> {
            backupThread.set(Thread.currentThread());
            firstReadStarted.countDown();
            assertThat(releaseFirstRead.await(10, TimeUnit.SECONDS)).isTrue();
            return new ReferenceImage(new ByteArrayResource(original), MediaType.IMAGE_PNG);
        });
        when(source.load(secondKey)).thenReturn(
                new ReferenceImage(new ByteArrayResource("second-original".getBytes(UTF_8)), MediaType.IMAGE_PNG));
        Map<String, GeneratedImage> objects = new ConcurrentHashMap<>();
        when(backupStorage.uploadIfAbsent(anyString(), any())).thenAnswer(invocation ->
                objects.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null
                        ? BackupUploadResult.UPLOADED : BackupUploadResult.ALREADY_EXISTS);
        when(backupStorage.download(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(objects.get(invocation.getArgument(0)))
                        .map(image -> new ReferenceImage(image.resource(), image.mediaType())));

        var clock = Clock.fixed(NOW, java.time.ZoneOffset.UTC);
        var s3 = new S3StorageProperties("source", "ap-northeast-2", "prod",
                ROOT.substring(0, ROOT.length() - 1), "harudle/references/generation/prod",
                DataSize.ofMegabytes(20), Duration.ofMinutes(15));
        var r2 = new R2StorageProperties(true, "prod", URI.create("https://example.r2.cloudflarestorage.com"),
                "backup", "fake-key", "fake-secret", Duration.ofMinutes(15), DataSize.ofMegabytes(20),
                Duration.ofSeconds(2), Duration.ofSeconds(2));

        new ApplicationContextRunner().withUserConfiguration(ImageBackupSchedulerConfiguration.class)
                .withPropertyValues("harudle.generation.storage.r2.backup-scheduler.enabled=true")
                .withBean(DiaryGenerationRepository.class, () -> generations)
                .withBean(DataSource.class, () -> dataSource)
                .withBean("serviceClock", Clock.class, () -> clock)
                .withBean(S3StorageProperties.class, () -> s3)
                .withBean(R2StorageProperties.class, () -> r2)
                .withBean(ImageBackupService.class, () -> new ImageBackupService(source, backupStorage, s3, r2, clock))
                .withBean("lifecycleProcessor", DefaultLifecycleProcessor.class, () -> {
                    var processor = new DefaultLifecycleProcessor() {
                        @Override
                        public void onClose() {
                            // ContextClosedEvent 전달이 끝난 뒤에만 첫 저장소 호출을 완료시킨다.
                            lifecycleStopStarted.countDown();
                            super.onClose();
                        }
                    };
                    processor.setTimeoutPerShutdownPhase(10_000);
                    return processor;
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    try (var closer = Executors.newSingleThreadExecutor()) {
                        try {
                            var scheduler = context.getBean(ImageBackupScheduler.class);
                            var executor = context.getBean("r2BackupTaskScheduler", ThreadPoolTaskScheduler.class);
                            var execution = executor.submit(scheduler::backupOriginals);
                            assertThat(firstReadStarted.await(10, TimeUnit.SECONDS)).isTrue();
                            var closing = closer.submit(context::close);
                            assertThat(lifecycleStopStarted.await(10, TimeUnit.SECONDS)).isTrue();
                            assertThat(backupThread.get().isInterrupted()).isFalse();
                            releaseFirstRead.countDown();
                            execution.get(10, TimeUnit.SECONDS);
                            closing.get(10, TimeUnit.SECONDS);

                            assertThat(objects).containsOnlyKeys(firstKey);
                            assertThat(objects.get(firstKey).resource().getContentAsByteArray()).isEqualTo(original);
                            verify(source, never()).exists(secondKey);
                            verify(source, never()).load(secondKey);
                            verify(generations, never()).findImageBackupTargetsAfter(
                                    any(), any(), anyString(), any(), any(), any());
                            verify(connection).prepareStatement("SELECT pg_advisory_unlock(?)");
                            verify(connection).close();
                            assertThat(output).contains("status=INTERRUPTED", "processedCount=1", "uploadedCount=1")
                                    .doesNotContain("status=COMPLETED");
                        } finally {
                            releaseFirstRead.countDown();
                        }
                    }
                });
    }
}
