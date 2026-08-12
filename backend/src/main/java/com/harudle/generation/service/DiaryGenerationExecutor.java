package com.harudle.generation.service;

import com.harudle.generation.service.dto.CompletedDiaryGeneration;
import com.harudle.generation.service.dto.GenerateDiaryImageCommand;
import java.util.UUID;

public interface DiaryGenerationExecutor {

    boolean isConfigured();

    CompletedDiaryGeneration generate(GenerateDiaryImageCommand command, UUID generationId);
}
