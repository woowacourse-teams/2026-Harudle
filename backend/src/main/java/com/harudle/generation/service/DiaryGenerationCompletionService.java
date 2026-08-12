package com.harudle.generation.service;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.domain.DiaryGeneration;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.repository.DiaryGenerationRepository;
import com.harudle.generation.service.exception.DiaryGenerationFailedException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiaryGenerationCompletionService {

    private final DiaryGenerationRepository diaryGenerationRepository;
    private final DiaryRepository diaryRepository;
    private final Clock clock;

    DiaryGenerationCompletionService(
            DiaryGenerationRepository diaryGenerationRepository,
            DiaryRepository diaryRepository,
            Clock clock
    ) {
        this.diaryGenerationRepository = diaryGenerationRepository;
        this.diaryRepository = diaryRepository;
        this.clock = clock;
    }

    @Transactional
    DiaryGeneration succeed(UUID generationId, Storyboard storyboard, String imageObjectKey) {
        DiaryGeneration generation = findForUpdate(generationId);
        return switch (generation.getStatus()) {
            case FAILED -> throw new DiaryGenerationFailedException(generation.getErrorCode());
            case SUCCEEDED -> generation;
            case PROCESSING -> {
                generation.succeed(storyboard, imageObjectKey, clock.instant());
                yield generation;
            }
        };
    }

    @Transactional
    GenerationErrorCode fail(UUID generationId, GenerationErrorCode errorCode) {
        DiaryGeneration generation = findForUpdate(generationId);
        Diary diary = findDiaryForUpdate(generation.getDiaryId());
        return switch (generation.getStatus()) {
            case PROCESSING -> {
                Instant failedAt = clock.instant();
                generation.fail(errorCode, failedAt);
                diary.delete(failedAt);
                yield errorCode;
            }
            case FAILED -> {
                diary.delete(generation.getCompletedAt());
                yield generation.getErrorCode();
            }
            case SUCCEEDED -> throw new IllegalStateException(
                    "성공한 그림일기 생성 기록을 실패 처리할 수 없습니다."
            );
        };
    }

    @Transactional
    boolean interruptIfStale(UUID generationId, Instant currentTime, java.time.Duration processingTimeout) {
        DiaryGeneration generation = findForUpdate(generationId);
        if (generation.getStatus() != GenerationStatus.PROCESSING) {
            return false;
        }
        generation.interruptIfStale(currentTime, processingTimeout);
        if (generation.getStatus() != GenerationStatus.FAILED) {
            return false;
        }
        Diary diary = findDiaryForUpdate(generation.getDiaryId());
        diary.delete(generation.getCompletedAt());
        return true;
    }

    private DiaryGeneration findForUpdate(UUID generationId) {
        return diaryGenerationRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException("그림일기 생성 기록을 찾을 수 없습니다."));
    }

    private Diary findDiaryForUpdate(UUID diaryId) {
        return diaryRepository.findByIdIncludingDeletedForUpdate(diaryId)
                .orElseThrow(() -> new IllegalStateException("일기 기록을 찾을 수 없습니다."));
    }
}
