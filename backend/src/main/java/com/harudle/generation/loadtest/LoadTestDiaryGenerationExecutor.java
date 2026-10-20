package com.harudle.generation.loadtest;

import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import java.util.UUID;

public final class LoadTestDiaryGenerationExecutor implements DiaryGenerationExecutor {

    private final DiaryGenerationExecutor delegate;

    public LoadTestDiaryGenerationExecutor(DiaryGenerationExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean isConfigured() {
        return delegate.isConfigured();
    }

    @Override
    public CompletedDiaryGeneration generate(GenerateDiaryImageCommand command, UUID generationId) {
        return delegate.generate(command, generationId);
    }
}
