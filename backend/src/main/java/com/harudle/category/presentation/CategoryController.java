package com.harudle.category.presentation;

import com.harudle.category.service.CategoryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
class CategoryController {

    private final CategoryQueryService categoryQueryService;

    CategoryController(CategoryQueryService categoryQueryService) {
        this.categoryQueryService = categoryQueryService;
    }

    @Operation(summary = "활성 카테고리 목록 조회")
    @GetMapping
    List<CategoryResponse> findAll() {
        return categoryQueryService.findPublicCategories().stream()
                .map(CategoryResponse::from)
                .toList();
    }
}
