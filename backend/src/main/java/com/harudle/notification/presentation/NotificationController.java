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

    @Operation(summary = "인앱 알림 목록", description = "본인이 수신한 공개 피드의 알림과 전체 미읽음 수를 최신순으로 조회합니다. "
            + "부모 피드·일기가 삭제됐거나 피드 작성자가 탈퇴한 알림은 목록과 미읽음 수에서 제외합니다. "
            + "LIKE 알림은 원인 좋아요의 ID·피드·행위자가 일치하는 실제 좋아요가 있을 때만 목록과 미읽음 수에 포함합니다. "
            + "좋아요 취소 시 숨기며, 재좋아요하면 새 좋아요의 새 알림만 미읽음으로 표시합니다. "
            + "알림 DB 이력은 보존하고 COMMENT 알림은 댓글이 삭제돼도 유지합니다.")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.INVALID_CURSOR,
            ErrorType.NOTIFICATION_UNAVAILABLE})
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<NotificationPageResponse> list(Authentication authentication,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(NotificationPageResponse.from(
                service.list(userIdResolver.resolve(authentication), cursor, size)));
    }

    @Operation(summary = "인앱 알림 읽음 처리", description = "클릭한 본인 알림 하나만 읽음 처리합니다. 반복 요청은 최초 읽음 시각을 보존합니다. "
            + "부모 피드·일기가 삭제됐거나 좋아요가 취소된 LIKE 알림은 404 NOTIFICATION_NOT_FOUND입니다. "
            + "피드 작성자가 탈퇴한 알림도 404 NOTIFICATION_NOT_FOUND입니다. "
            + "재좋아요해도 예전 좋아요 알림을 다시 읽음 처리할 수는 없습니다.")
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
