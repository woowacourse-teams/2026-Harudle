package com.harudle.category.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.category.service.CategoryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
class CategoryController {

    private final CategoryQueryService categoryQueryService;
    private final AuthenticatedUserIdResolver userIdResolver;

    CategoryController(CategoryQueryService categoryQueryService, AuthenticatedUserIdResolver userIdResolver) {
        this.categoryQueryService = categoryQueryService;
        this.userIdResolver = userIdResolver;
    }

    @Operation(summary = "활성 카테고리 목록 조회")
    @GetMapping
    List<CategoryResponse> findAll(Authentication authentication) {
        return categoryQueryService.findPublicCategories(userIdResolver.resolveOptional(authentication).orElse(null)).stream()
                .map(CategoryResponse::from)
                .toList();
    }
}
