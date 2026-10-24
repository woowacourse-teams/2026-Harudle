package com.harudle.diary.service;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryLifecycleRepository;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.diary.service.exception.DiaryNotPublishableException;
import com.harudle.diary.service.port.DiaryPublicationReader;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationQueryRepository;
import com.harudle.generation.diary.repository.DiaryGenerationSnapshot;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultDiaryPublicationReader implements DiaryPublicationReader {

    private final DiaryLifecycleRepository diaries;
    private final DiaryGenerationQueryRepository generations;

    public DefaultDiaryPublicationReader(
            DiaryLifecycleRepository diaries,
            DiaryGenerationQueryRepository generations
    ) {
        this.diaries = diaries;
        this.generations = generations;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PublishableDiary lockAndRead(UUID actorId, UUID diaryId) {
        Objects.requireNonNull(actorId, "사용자 ID는 필수입니다.");
        Objects.requireNonNull(diaryId, "일기 ID는 필수입니다.");
        Diary diary = diaries.findByIdIncludingDeletedForUpdate(diaryId)
                .orElseThrow(DiaryNotFoundException::new);
        if (diary.isDeleted()) {
            throw new DiaryNotFoundException();
        }
        if (!diary.isOwnedBy(actorId)) {
            throw new DiaryAccessDeniedException();
        }
        // 생성 기록은 잠그지 않는다. 생성 처리의 generation -> diary 잠금과 역전되지 않게 한다.
        DiaryGenerationSnapshot generation = generations.findSnapshotByDiaryId(diaryId)
                .orElseThrow(DiaryNotPublishableException::new);
        if (generation.status() != GenerationStatus.SUCCEEDED
                || generation.imageObjectKey() == null || generation.imageObjectKey().isBlank()) {
            throw new DiaryNotPublishableException();
        }
        return new PublishableDiary(diary.getId(), diary.getUserId(), generation.imageObjectKey());
    }
}
