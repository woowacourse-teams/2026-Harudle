package com.harudle.category.presentation;

import com.harudle.category.service.CategoryCreateService;
import com.harudle.category.service.CategoryQueryService;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/categories")
class AdminCategoryController {

    private final CategoryQueryService categoryQueryService;
    private final CategoryCreateService categoryCreateService;

    AdminCategoryController(CategoryQueryService categoryQueryService, CategoryCreateService categoryCreateService) {
        this.categoryQueryService = categoryQueryService;
        this.categoryCreateService = categoryCreateService;
    }

    @Operation(summary = "관리자 전체 카테고리 목록 조회")
    @ApiErrorResponses({ErrorType.UNAUTHORIZED, ErrorType.FORBIDDEN})
    @GetMapping
    List<CategoryResponse> findAll() {
        return categoryQueryService.findAdminCategories().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    @Operation(summary = "관리자 카테고리 추가")
    @ApiErrorResponses({
            ErrorType.UNAUTHORIZED,
            ErrorType.FORBIDDEN,
            ErrorType.VALIDATION_ERROR,
            ErrorType.CATEGORY_NAME_ALREADY_USED
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CategoryResponse create(@Valid @RequestBody CreateCategoryRequest request) {
        return CategoryResponse.from(categoryCreateService.create(request.name(), request.sortOrder()));
    }
}
