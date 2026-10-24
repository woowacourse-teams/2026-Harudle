package com.harudle.generation.diary.service;

import java.util.UUID;
import org.slf4j.MDC;

final class GenerationLogContext {

    private static final String GENERATION_ID = "generationId";

    private GenerationLogContext() {
    }

    static void withGenerationId(UUID generationId, Runnable recording) {
        String previousGenerationId = MDC.get(GENERATION_ID);
        MDC.put(GENERATION_ID, generationId.toString());
        try {
            recording.run();
        } finally {
            if (previousGenerationId == null) {
                MDC.remove(GENERATION_ID);
            } else {
                MDC.put(GENERATION_ID, previousGenerationId);
            }
        }
    }
}
