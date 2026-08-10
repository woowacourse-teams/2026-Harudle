package com.harudle.generation.service;

import com.harudle.generation.domain.ComicGeneration;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.repository.ComicGenerationRepository;
import com.harudle.generation.service.exception.ComicGenerationFailedException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComicGenerationCompletionService {

    private final ComicGenerationRepository comicGenerationRepository;
    private final Clock clock;

    public ComicGenerationCompletionService(
            ComicGenerationRepository comicGenerationRepository,
            Clock clock
    ) {
        this.comicGenerationRepository = comicGenerationRepository;
        this.clock = clock;
    }

    @Transactional
    public ComicGeneration succeed(UUID generationId, Storyboard storyboard, String imageObjectKey) {
        ComicGeneration generation = findForUpdate(generationId);
        if (generation.getStatus() == GenerationStatus.FAILED) {
            throw new ComicGenerationFailedException(generation.getErrorCode());
        }
        if (generation.getStatus() == GenerationStatus.SUCCEEDED) {
            return generation;
        }
        generation.succeed(storyboard, imageObjectKey, clock.instant());
        return generation;
    }

    @Transactional
    public GenerationErrorCode fail(UUID generationId, GenerationErrorCode errorCode) {
        ComicGeneration generation = findForUpdate(generationId);
        if (generation.getStatus() == GenerationStatus.PROCESSING) {
            generation.fail(errorCode, clock.instant());
        }
        if (generation.getStatus() == GenerationStatus.FAILED) {
            return generation.getErrorCode();
        }
        throw new IllegalStateException("성공한 만화 생성 기록을 실패 처리할 수 없습니다.");
    }

    private ComicGeneration findForUpdate(UUID generationId) {
        return comicGenerationRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException("만화 생성 기록을 찾을 수 없습니다."));
    }
}
