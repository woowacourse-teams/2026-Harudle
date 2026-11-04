package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.harudle.auth.domain.WithdrawalImageDeletion;
import com.harudle.auth.domain.WithdrawalImageDeletion.Storage;
import com.harudle.auth.infrastructure.S3ProfileImageStorage;
import com.harudle.auth.infrastructure.WithdrawalImageRepository;
import com.harudle.auth.infrastructure.WithdrawalImageRepository.ImageTargets;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.ImageStorage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class WithdrawalImageCleanupServiceTest {

    @Mock private ObjectProvider<ImageStorage> diaryProvider;
    @Mock private ObjectProvider<S3ProfileImageStorage> profileProvider;
    @Mock private ObjectProvider<BackupObjectStorage> backupProvider;
    @Mock private ImageStorage diaryStorage;
    @Mock private S3ProfileImageStorage profileStorage;
    @Mock private BackupObjectStorage backupStorage;
    @Mock private WithdrawalImageRepository images;

    @Test
    @DisplayName("이미지 삭제가 실패해도 다른 원본과 프로필 삭제를 계속 시도한다")
    void continueAfterDeletionFailure() {
        String folder = "harudle/generated/diary-images/dev/diary-id/";
        String detailKey = folder + "image-960.webp";
        String profileKey = "harudle/profile-images/dev/user/image.webp";
        when(diaryProvider.getIfAvailable()).thenReturn(diaryStorage);
        when(backupProvider.getIfAvailable()).thenReturn(backupStorage);
        when(profileProvider.getIfAvailable()).thenReturn(profileStorage);
        doThrow(new IllegalStateException()).when(diaryStorage).delete(detailKey);
        doThrow(new IllegalStateException()).when(backupStorage).delete(folder + "image.png");

        var service = service();
        Logger logger = (Logger) LoggerFactory.getLogger(WithdrawalImageCleanupService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.delete(new ImageTargets(List.of(detailKey), List.of(profileKey)));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        assertThat(appender.list).hasSize(2).allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain(folder, profileKey));
        verify(backupStorage).delete(folder + "image.jpg");
        verify(backupStorage).delete(folder + "image.webp");
        verify(profileStorage).delete(profileKey);
        verify(images).removeCleanupTask(new WithdrawalImageDeletion(Storage.S3_PROFILE, profileKey));
        verify(images, never()).removeCleanupTask(new WithdrawalImageDeletion(Storage.S3_DIARY, detailKey));
        verify(images, never()).removeCleanupTask(new WithdrawalImageDeletion(Storage.R2, folder + "image.png"));
    }

    @Test
    @DisplayName("저장소가 구성되지 않았으면 삭제 목록을 제거하지 않는다")
    void reportUnavailableStorage() {
        String originalKey = "harudle/generated/diary-images/dev/diary-id/image.png";
        var service = service();

        assertThatCode(() -> service.delete(new ImageTargets(List.of(originalKey), List.of())))
                .doesNotThrowAnyException();

        verifyNoInteractions(images);
    }

    @Test
    @DisplayName("파일 삭제 후 목록 제거가 실패해도 예외를 전파하거나 자동 재시도하지 않는다")
    void keepsFailureWhenRemovingCompletedTaskFails() {
        var task = new WithdrawalImageDeletion(Storage.S3_PROFILE, "profile/removed.webp");
        when(profileProvider.getIfAvailable()).thenReturn(profileStorage);
        doThrow(new IllegalStateException()).when(images).removeCleanupTask(task);

        assertThatCode(() -> service().delete(new ImageTargets(List.of(), List.of(task.objectKey()))))
                .doesNotThrowAnyException();

        verify(profileStorage).delete(task.objectKey());
        verify(images).removeCleanupTask(task);
    }

    private WithdrawalImageCleanupService service() {
        return new WithdrawalImageCleanupService(diaryProvider, profileProvider, backupProvider, images);
    }

}
