package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.feed.service.exception.FeedIntegrationUnavailableException;
import com.harudle.feed.service.port.FeedLifecycle;
import com.harudle.push.service.port.FeedPushOutbox;
import com.harudle.share.repository.ShareLinkRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiaryDeletionServiceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID OTHER_USER_ID = UUID.fromString("fcd41d4a-2cce-4f28-bdb7-524d00ef4da6");
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    @Mock
    private DiaryRepository diaryRepository;

    @Mock
    private ShareLinkRepository shareLinkRepository;

    @Mock
    private FeedLifecycle feedLifecycle;

    private DiaryDeletionService diaryDeletionService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        diaryDeletionService = new DiaryDeletionService(diaryRepository, shareLinkRepository, feedLifecycle, clock);
    }

    @Test
    @DisplayName("본인 일기 삭제와 같은 시각으로 연결 피드도 삭제하고 기존 공유 링크를 정리한다")
    void deleteOwnedDiary() {
        Diary diary = createDiary(USER_ID);
        when(diaryRepository.findActiveById(diary.getId())).thenReturn(Optional.of(diary));

        diaryDeletionService.delete(USER_ID, diary.getId());

        assertThat(diary.isDeleted()).isTrue();
        verify(shareLinkRepository).deleteAllByDiaryId(diary.getId());
        verify(feedLifecycle).deleteByDiary(diary.getId(), NOW);
    }

    @Test
    @DisplayName("존재하지 않는 일기 삭제는 성공으로 처리한다")
    void deleteMissingDiaryIsIdempotent() {
        UUID diaryId = UUID.randomUUID();
        when(diaryRepository.findActiveById(diaryId)).thenReturn(Optional.empty());

        diaryDeletionService.delete(USER_ID, diaryId);

        verify(shareLinkRepository, never()).deleteAllByDiaryId(diaryId);
        verifyNoInteractions(feedLifecycle);
    }

    @Test
    @DisplayName("다른 사용자의 일기는 삭제할 수 없다")
    void deleteRejectsOtherUsersDiary() {
        Diary diary = createDiary(OTHER_USER_ID);
        when(diaryRepository.findActiveById(diary.getId())).thenReturn(Optional.of(diary));

        assertThatThrownBy(() -> diaryDeletionService.delete(USER_ID, diary.getId()))
                .isInstanceOf(DiaryAccessDeniedException.class);
        verify(shareLinkRepository, never()).deleteAllByDiaryId(diary.getId());
        assertThat(diary.isDeleted()).isFalse();
        verifyNoInteractions(feedLifecycle);
    }

    @Test
    @DisplayName("이미 삭제된 일기의 반복 삭제는 연결 피드를 다시 삭제하지 않는다")
    void deleteAlreadyDeletedDiaryDoesNotRepeatFeedDeletion() {
        Diary diary = createDiary(USER_ID);
        when(diaryRepository.findActiveById(diary.getId())).thenReturn(Optional.of(diary)).thenReturn(Optional.empty());

        diaryDeletionService.delete(USER_ID, diary.getId());
        diaryDeletionService.delete(USER_ID, diary.getId());

        verify(feedLifecycle).deleteByDiary(diary.getId(), NOW);
        verify(shareLinkRepository).deleteAllByDiaryId(diary.getId());
    }

    @Test
    @DisplayName("일기와 피드에 DB 정밀도에 맞춘 같은 삭제 시각을 전달한다")
    void usesSameMicrosecondTimestampForDiaryAndFeed() {
        Instant preciseTime = NOW.plusNanos(123_456_789);
        diaryDeletionService = new DiaryDeletionService(diaryRepository, shareLinkRepository, feedLifecycle,
                Clock.fixed(preciseTime, ZoneOffset.UTC));
        Diary diary = createDiary(USER_ID);
        when(diaryRepository.findActiveById(diary.getId())).thenReturn(Optional.of(diary));

        diaryDeletionService.delete(USER_ID, diary.getId());

        Instant expected = preciseTime.truncatedTo(ChronoUnit.MICROS);
        assertThat(diary).extracting("deletedAt").isEqualTo(expected);
        verify(feedLifecycle).deleteByDiary(diary.getId(), expected);
    }

    @Test
    @DisplayName("피드 삭제 실패를 전파하여 호출 트랜잭션이 롤백하도록 한다")
    void propagatesFeedDeletionFailure() {
        Diary diary = createDiary(USER_ID);
        when(diaryRepository.findActiveById(diary.getId())).thenReturn(Optional.of(diary));
        doThrow(new IllegalStateException("푸시 취소 실패")).when(feedLifecycle).deleteByDiary(diary.getId(), NOW);

        assertThatThrownBy(() -> diaryDeletionService.delete(USER_ID, diary.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("푸시 취소 실패");
    }

    @Test
    @DisplayName("푸시 구현이 미연결인 경우 피드 연동 오류를 전파한다")
    void propagatesUnavailablePushAdapter() {
        Diary diary = createDiary(USER_ID);
        when(diaryRepository.findActiveById(diary.getId())).thenReturn(Optional.of(diary));
        doThrow(new FeedIntegrationUnavailableException(FeedPushOutbox.class))
                .when(feedLifecycle).deleteByDiary(diary.getId(), NOW);

        assertThatThrownBy(() -> diaryDeletionService.delete(USER_ID, diary.getId()))
                .isInstanceOf(FeedIntegrationUnavailableException.class);
    }

    private Diary createDiary(UUID userId) {
        return Diary.create(userId, LocalDate.of(2026, 8, 6), "오늘의 일기");
    }
}
