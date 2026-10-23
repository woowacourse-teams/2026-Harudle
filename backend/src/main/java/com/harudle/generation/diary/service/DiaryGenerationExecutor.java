package com.harudle.generation.diary.service;

import com.harudle.generation.diary.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import java.util.UUID;

public interface DiaryGenerationExecutor {

    boolean isConfigured();

    CompletedDiaryGeneration generate(GenerateDiaryImageCommand command, UUID generationId);
}
