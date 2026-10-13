package com.harudle.notification.service.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record NotificationPageResult(List<NotificationResult> items, @Nullable String nextCursor,
        boolean hasNext, long unreadCount) {
    public NotificationPageResult {
        items = List.copyOf(items);
        if (hasNext != (nextCursor != null) || unreadCount < 0) {
            throw new IllegalArgumentException("알림 페이지의 커서와 미읽음 수가 올바르지 않습니다.");
        }
    }
}
