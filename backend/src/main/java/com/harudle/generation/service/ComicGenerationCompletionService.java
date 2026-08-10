package com.harudle.generation.service;

import com.harudle.generation.domain.ComicGeneration;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.repository.ComicGenerationRepository;
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
        generation.succeed(storyboard, imageObjectKey, clock.instant());
        return generation;
    }

    @Transactional
    public void fail(UUID generationId, GenerationErrorCode errorCode) {
        ComicGeneration generation = findForUpdate(generationId);
        if (generation.getStatus() == GenerationStatus.PROCESSING) {
            generation.fail(errorCode, clock.instant());
        }
    }

    private ComicGeneration findForUpdate(UUID generationId) {
        return comicGenerationRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException("만화 생성 기록을 찾을 수 없습니다."));
    }
}
