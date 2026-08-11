package com.harudle.generation.service;

import com.harudle.generation.service.dto.CompletedComicGeneration;
import com.harudle.generation.service.dto.GenerateComicCommand;
import java.util.UUID;

public interface ComicGenerationExecutor {

    boolean isConfigured();

    CompletedComicGeneration generate(GenerateComicCommand command, UUID generationId);
}
