package com.harudle.generation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.harudle.generation.diary.service.ImageBackupService;
import com.harudle.generation.diary.service.ImageRecoveryService;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.ImageStorage;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

class ImageBackupConfigurationTest {

    private final ImageStorage source = mock(ImageStorage.class);
    private final BackupObjectStorage backup = mock(BackupObjectStorage.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ImageBackupConfiguration.class, ImageRecoveryConfiguration.class);

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    @DisplayName("S3 생성 어댑터와 R2가 모두 활성화되어야 백업 서비스를 구성한다")
    void requireBothStorages(boolean sourceEnabled, boolean backupEnabled) {
        runner.withPropertyValues(
                "harudle.generation.adapters.enabled=" + sourceEnabled,
                "harudle.generation.storage.r2.enabled=" + backupEnabled
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ImageBackupService.class);
            assertThat(context).doesNotHaveBean(ImageRecoveryService.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("개발과 운영에서 내부 백업 서비스를 구성하되 시작 시 백업을 실행하지 않는다")
    void configureInternalService(String environment) {
        enabledRunner(environment, environment).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ImageBackupService.class);
            assertThat(context).hasSingleBean(ImageRecoveryService.class);
            verifyNoInteractions(source, backup);
        });
    }

    @Test
    @DisplayName("S3와 R2 환경이 다르면 서비스 시작을 거절한다")
    void rejectDifferentEnvironments() {
        enabledRunner("dev", "prod").run(context -> {
            assertThat(context).hasFailed();
            verifyNoInteractions(source, backup);
        });
    }

    private ApplicationContextRunner enabledRunner(String sourceEnvironment, String backupEnvironment) {
        return runner.withPropertyValues(
                "harudle.generation.adapters.enabled=true",
                "harudle.generation.storage.r2.enabled=true"
        ).withBean("imageStorage", ImageStorage.class, () -> source)
                .withBean(BackupObjectStorage.class, () -> backup)
                .withBean("serviceClock", Clock.class, Clock::systemUTC)
                .withBean("authClock", Clock.class, Clock::systemUTC)
                .withBean(S3StorageProperties.class, () -> new S3StorageProperties(
                        "test-source", "ap-northeast-2", sourceEnvironment,
                        "harudle/generated/diary-images/" + sourceEnvironment,
                        "harudle/references/generation/" + sourceEnvironment,
                        DataSize.ofMegabytes(20), Duration.ofMinutes(15)))
                .withBean(R2StorageProperties.class, () -> new R2StorageProperties(
                        true, backupEnvironment, URI.create("https://example.r2.cloudflarestorage.com"),
                        "test-backup", "test-key", "test-secret", Duration.ofMinutes(15), DataSize.ofMegabytes(20),
                        Duration.ofSeconds(2), Duration.ofSeconds(2)));
    }
}
