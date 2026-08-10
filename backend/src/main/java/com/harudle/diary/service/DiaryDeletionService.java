package com.harudle.diary.service;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.share.repository.ShareLinkRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiaryDeletionService {

    private final DiaryRepository diaryRepository;
    private final ShareLinkRepository shareLinkRepository;
    private final Clock clock;

    public DiaryDeletionService(
            DiaryRepository diaryRepository,
            ShareLinkRepository shareLinkRepository,
            Clock clock
    ) {
        this.diaryRepository = diaryRepository;
        this.shareLinkRepository = shareLinkRepository;
        this.clock = clock;
    }

    @Transactional
    public void delete(UUID userId, UUID diaryId) {
        validateParameters(userId, diaryId);
        Optional<Diary> diary = diaryRepository.findById(diaryId);
        if (diary.isEmpty() || diary.get().isDeleted()) {
            return;
        }
        deleteOwnedDiary(diary.get(), userId);
    }

    private void deleteOwnedDiary(Diary diary, UUID userId) {
        if (!diary.isOwnedBy(userId)) {
            throw new DiaryAccessDeniedException();
        }
        shareLinkRepository.deleteByDiaryId(diary.getId());
        diary.delete(clock.instant());
    }

    private static void validateParameters(UUID userId, UUID diaryId) {
        if (userId == null) {
            throw new IllegalArgumentException("사용자 ID는 필수입니다.");
        }
        if (diaryId == null) {
            throw new IllegalArgumentException("일기 ID는 필수입니다.");
        }
    }
}
