package com.harudle.admin.presentation;

import com.harudle.admin.repository.AdminUserPage;
import com.harudle.admin.service.AdminUserQueryService;
import com.harudle.admin.service.AdminUserUsageCommandService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminUserQueryService adminUserQueryService;
    private final AdminUserUsageCommandService adminUserUsageCommandService;

    AdminUserController(AdminUserQueryService adminUserQueryService,
                        AdminUserUsageCommandService adminUserUsageCommandService) {
        this.adminUserQueryService = adminUserQueryService;
        this.adminUserUsageCommandService = adminUserUsageCommandService;
    }

    @GetMapping
    AdminUserSearchResponse search(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size
    ) {
        AdminUserPage result = adminUserQueryService.search(query, page, size);
        return AdminUserSearchResponse.from(result, page, size);
    }

    @GetMapping("/{userId}")
    AdminUserDetailResponse findDetail(@PathVariable java.util.UUID userId) {
        return AdminUserDetailResponse.from(adminUserQueryService.findDetail(userId));
    }

    @PutMapping("/{userId}/generation-limit")
    void changeDailyGenerationLimit(@PathVariable java.util.UUID userId,
                                    @RequestBody @jakarta.validation.Valid ChangeDailyGenerationLimitRequest request) {
        adminUserUsageCommandService.changeDailyGenerationLimit(userId, request.limitCount());
    }

    @PutMapping("/{userId}/usage")
    AdminUserDetailResponse changeUsedCount(
            @PathVariable java.util.UUID userId,
            @RequestBody @jakarta.validation.Valid ChangeUsedCountRequest request
    ) {
        adminUserUsageCommandService.changeUsedCount(userId, request.usedCount());
        return AdminUserDetailResponse.from(adminUserQueryService.findDetail(userId));
    }
}
