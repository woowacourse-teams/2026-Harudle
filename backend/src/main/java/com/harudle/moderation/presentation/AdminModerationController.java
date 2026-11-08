package com.harudle.moderation.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.moderation.domain.ModerationChange;
import com.harudle.moderation.domain.ModerationState;
import com.harudle.moderation.domain.ModerationTargetType;
import com.harudle.moderation.service.AdminModerationService;
import com.harudle.moderation.service.ModerationHistoryPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/moderation/{targetType}/{targetId}")
public class AdminModerationController {
    private final AdminModerationService service;
    private final AuthenticatedUserIdResolver userIds;

    public AdminModerationController(AdminModerationService service, AuthenticatedUserIdResolver userIds) {
        this.service = service;
        this.userIds = userIds;
    }

    @GetMapping
    ResponseEntity<ModerationState> find(Authentication authentication,
            @PathVariable ModerationTargetType targetType, @PathVariable UUID targetId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                service.find(userIds.resolve(authentication), targetType, targetId));
    }

    @PutMapping
    ResponseEntity<ModerationState> change(Authentication authentication,
            @PathVariable ModerationTargetType targetType, @PathVariable UUID targetId,
            @Valid @RequestBody ChangeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                service.change(userIds.resolve(authentication), targetType, targetId,
                        new ModerationChange(request.restricted(), request.expectedVersion(), request.reason())));
    }

    @GetMapping("/history")
    ResponseEntity<ModerationHistoryPage> history(Authentication authentication,
            @PathVariable ModerationTargetType targetType, @PathVariable UUID targetId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(Integer.MAX_VALUE / 100) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                service.history(userIds.resolve(authentication), targetType, targetId, page, size));
    }

    public record ChangeRequest(@NotNull Boolean restricted, @NotNull Long expectedVersion, @NotNull String reason) {
    }
}
