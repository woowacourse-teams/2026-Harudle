package com.harudle.admin.presentation;

import com.harudle.admin.repository.AdminGenerationPage;
import com.harudle.admin.service.AdminGenerationQueryService;
import com.harudle.generation.domain.GenerationStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
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
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final AdminGenerationQueryService service;
    AdminGenerationController(AdminGenerationQueryService service) { this.service = service; }

    @GetMapping
    AdminGenerationSearchResponse search(@RequestParam(required = false) UUID userId,
                                         @RequestParam(required = false) GenerationStatus status,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        InstantRange range = new InstantRange(from, to);
        return AdminGenerationSearchResponse.from(service.search(userId, status, range.from(), range.to(), page, size), page, size);
    }

    private record InstantRange(Instant from, Instant to) {
        InstantRange(LocalDate from, LocalDate to) {
            this(from == null ? null : from.atStartOfDay(ZONE).toInstant(),
                    to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toInstant());
        }
    }
}
