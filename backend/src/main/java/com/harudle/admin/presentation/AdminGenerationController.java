package com.harudle.admin.presentation;

import com.harudle.admin.presentation.dto.AdminGenerationHistoryResponse;
import com.harudle.admin.query.AdminGenerationHistoryPage;
import com.harudle.admin.service.AdminGenerationHistoryQueryService;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.generation.diary.domain.GenerationStatus;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/generations")
class AdminGenerationController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    private final AdminGenerationHistoryQueryService generationHistoryQueryService;

    AdminGenerationController(AdminGenerationHistoryQueryService generationHistoryQueryService) {
        this.generationHistoryQueryService = generationHistoryQueryService;
    }

    @GetMapping
    @ApiErrorResponses({
            ErrorType.VALIDATION_ERROR,
            ErrorType.UNAUTHORIZED,
            ErrorType.FORBIDDEN
    })
    AdminGenerationHistoryResponse search(
            @Parameter(description = "조회할 사용자 ID (UUID)")
            @RequestParam(required = false) UUID userId,
            @Parameter(description = "생성 상태 (PROCESSING, SUCCEEDED, FAILED)")
            @RequestParam(required = false) GenerationStatus status,
            @Parameter(description = "KST 기준 생성 요청일 시작일 (포함, YYYY-MM-DD)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "KST 기준 생성 요청일 종료일 (포함, YYYY-MM-DD)")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "페이지 번호 (0부터 시작)")
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @Parameter(description = "페이지당 항목 수 (1~100)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size
    ) {
        AdminGenerationHistoryPage result = generationHistoryQueryService.search(
                userId,
                status,
                from,
                to,
                page,
                size
        );
        return AdminGenerationHistoryResponse.from(result, page, size);
    }
}
