package com.harudle.admin.presentation;

import com.harudle.admin.presentation.dto.AdminStatsResponse;
import com.harudle.admin.service.AdminUserQueryService;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/stats")
class AdminStatsController {

    private final AdminUserQueryService adminUserQueryService;

    AdminStatsController(AdminUserQueryService adminUserQueryService) {
        this.adminUserQueryService = adminUserQueryService;
    }

    @GetMapping
    @ApiErrorResponses({
            ErrorType.UNAUTHORIZED,
            ErrorType.FORBIDDEN
    })
    AdminStatsResponse find() {
        return AdminStatsResponse.from(adminUserQueryService.findMemberStats());
    }
}
