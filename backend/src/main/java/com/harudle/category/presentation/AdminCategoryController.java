package com.harudle.category.presentation;

import com.harudle.category.service.CategoryQueryService;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/categories")
class AdminCategoryController {

    private final CategoryQueryService categoryQueryService;

    AdminCategoryController(CategoryQueryService categoryQueryService) {
        this.categoryQueryService = categoryQueryService;
    }

    @Operation(summary = "관리자 전체 카테고리 목록 조회")
    @ApiErrorResponses({ErrorType.UNAUTHORIZED, ErrorType.FORBIDDEN})
    @GetMapping
    List<CategoryResponse> findAll() {
        return categoryQueryService.findAdminCategories().stream()
                .map(CategoryResponse::from)
                .toList();
    }
}
