package com.harudle.notification.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@Tag(name = "Notification", description = "본인 인앱 알림")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {
    private final NotificationService service;
    private final AuthenticatedUserIdResolver userIdResolver;

    NotificationController(NotificationService service, AuthenticatedUserIdResolver userIdResolver) {
        this.service = service;
        this.userIdResolver = userIdResolver;
    }

    @Operation(summary = "인앱 알림 목록", description = "본인이 수신한 공개 피드의 알림과 전체 미읽음 수를 최신순으로 조회합니다.")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.INVALID_CURSOR,
            ErrorType.NOTIFICATION_UNAVAILABLE})
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<NotificationPageResponse> list(Authentication authentication,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(NotificationPageResponse.from(
                service.list(userIdResolver.resolve(authentication), cursor, size)));
    }

    @Operation(summary = "인앱 알림 읽음 처리", description = "클릭한 본인 알림 하나만 읽음 처리합니다. 반복 요청은 최초 읽음 시각을 보존합니다.")
    @ApiResponse(responseCode = "204", description = "알림 읽음 처리 완료")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.INVALID_CSRF_TOKEN,
            ErrorType.FORBIDDEN, ErrorType.NOTIFICATION_NOT_FOUND})
    @PatchMapping("/{notificationId}")
    ResponseEntity<Void> markRead(Authentication authentication, @PathVariable UUID notificationId,
            @Valid @RequestBody ReadNotificationRequest request) {
        service.markRead(userIdResolver.resolve(authentication), notificationId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
