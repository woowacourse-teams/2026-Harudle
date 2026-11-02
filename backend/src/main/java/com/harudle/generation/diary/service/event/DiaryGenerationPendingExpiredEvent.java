package com.harudle.generation.diary.service.event;

import java.util.UUID;

public record DiaryGenerationPendingExpiredEvent(UUID generationId) {
}
