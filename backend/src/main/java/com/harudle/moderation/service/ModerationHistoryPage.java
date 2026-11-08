package com.harudle.moderation.service;

import com.harudle.moderation.domain.ModerationEvent;
import java.util.List;

public record ModerationHistoryPage(List<ModerationEvent> content, int page, int size,
        long totalElements, long totalPages, boolean hasNext) {
    public ModerationHistoryPage {
        content = List.copyOf(content);
    }
}
