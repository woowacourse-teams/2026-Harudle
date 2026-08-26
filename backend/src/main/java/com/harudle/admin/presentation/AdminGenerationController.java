package com.harudle.admin.presentation;

import com.harudle.admin.presentation.dto.AdminGenerationHistoryResponse;
import com.harudle.admin.query.AdminGenerationHistoryPage;
import com.harudle.admin.service.AdminGenerationHistoryQueryService;
import com.harudle.generation.domain.GenerationStatus;
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

    private final AdminGenerationHistoryQueryService generationHistoryQueryService;

    AdminGenerationController(AdminGenerationHistoryQueryService generationHistoryQueryService) {
        this.generationHistoryQueryService = generationHistoryQueryService;
    }

    @GetMapping
    AdminGenerationHistoryResponse search(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) GenerationStatus status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
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
