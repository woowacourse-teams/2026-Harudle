package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.share.repository.ShareLinkRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    private DiaryDeletionService diaryDeletionService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        diaryDeletionService = new DiaryDeletionService(diaryRepository, shareLinkRepository, clock);
    }

    @Test
    @DisplayName("본인 일기를 소프트 삭제하고 연결된 공유 링크를 삭제한다")
    void deleteOwnedDiary() {
        Diary diary = createDiary(USER_ID);
        when(diaryRepository.findById(diary.getId())).thenReturn(Optional.of(diary));

        diaryDeletionService.delete(USER_ID, diary.getId());

        assertThat(diary.isDeleted()).isTrue();
        verify(shareLinkRepository).deleteByDiaryId(diary.getId());
    }

    @Test
    @DisplayName("존재하지 않는 일기 삭제는 성공으로 처리한다")
    void deleteMissingDiaryIsIdempotent() {
        UUID diaryId = UUID.randomUUID();
        when(diaryRepository.findById(diaryId)).thenReturn(Optional.empty());

        diaryDeletionService.delete(USER_ID, diaryId);

        verify(shareLinkRepository, never()).deleteByDiaryId(diaryId);
    }

    @Test
    @DisplayName("다른 사용자의 일기는 삭제할 수 없다")
    void deleteRejectsOtherUsersDiary() {
        Diary diary = createDiary(OTHER_USER_ID);
        when(diaryRepository.findById(diary.getId())).thenReturn(Optional.of(diary));

        assertThatThrownBy(() -> diaryDeletionService.delete(USER_ID, diary.getId()))
                .isInstanceOf(DiaryAccessDeniedException.class);
        verify(shareLinkRepository, never()).deleteByDiaryId(diary.getId());
    }

    private Diary createDiary(UUID userId) {
        return Diary.create(userId, LocalDate.of(2026, 8, 6), "오늘의 일기");
    }
}
