package com.harudle.generation.diary.service;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.exception.DiaryGenerationFailedException;
import com.harudle.generation.usage.service.GenerationUsageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiaryGenerationCompletionService {

    private final DiaryGenerationRepository diaryGenerationRepository;
    private final DiaryRepository diaryRepository;
    private final GenerationUsageService generationUsageService;
    private final Clock clock;

    DiaryGenerationCompletionService(
            DiaryGenerationRepository diaryGenerationRepository,
            DiaryRepository diaryRepository,
            GenerationUsageService generationUsageService,
            @Qualifier("serviceClock")
            Clock clock
    ) {
        this.diaryGenerationRepository = diaryGenerationRepository;
        this.diaryRepository = diaryRepository;
        this.generationUsageService = generationUsageService;
        this.clock = clock;
    }

    @Transactional
    DiaryGeneration succeed(
            UUID generationId,
            Storyboard storyboard,
            String imageObjectKey,
            GenerationTokenUsage tokenUsage
    ) {
        DiaryGeneration generation = findForUpdate(generationId);
        return switch (generation.getStatus()) {
            case FAILED -> throw new DiaryGenerationFailedException(generation.getErrorCode());
            case SUCCEEDED -> generation;
            case PROCESSING -> {
                generation.succeed(storyboard, imageObjectKey, tokenUsage, clock.instant());
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
                restoreUsage(generation, diary);
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
    public boolean interruptIfStale(UUID generationId, Instant currentTime, Duration processingTimeout) {
        DiaryGeneration generation = findForUpdate(generationId);
        if (!generation.interruptIfStale(currentTime, processingTimeout)) {
            return false;
        }
        Diary diary = findDiaryForUpdate(generation.getDiaryId());
        diary.delete(generation.getCompletedAt());
        restoreUsage(generation, diary);
        return true;
    }

    private void restoreUsage(DiaryGeneration generation, Diary diary) {
        if (generation.getUsageDate() != null) {
            generationUsageService.restoreUsage(diary.getUserId(), generation.getUsageDate());
        }
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
