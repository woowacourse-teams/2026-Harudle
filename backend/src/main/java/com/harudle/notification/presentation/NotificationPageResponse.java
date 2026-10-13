package com.harudle.notification.presentation;

import com.harudle.notification.service.dto.NotificationPageResult;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record NotificationPageResponse(List<NotificationResponse> items, @Nullable String nextCursor,
        boolean hasNext, long unreadCount) {
    static NotificationPageResponse from(NotificationPageResult page) {
        return new NotificationPageResponse(page.items().stream().map(NotificationResponse::from).toList(),
                page.nextCursor(), page.hasNext(), page.unreadCount());
    }
}
