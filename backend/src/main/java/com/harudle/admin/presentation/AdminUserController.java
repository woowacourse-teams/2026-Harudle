package com.harudle.admin.presentation;

import com.harudle.admin.repository.AdminUserPage;
import com.harudle.admin.service.AdminUserQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminUserQueryService adminUserQueryService;

    AdminUserController(AdminUserQueryService adminUserQueryService) {
        this.adminUserQueryService = adminUserQueryService;
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
}
