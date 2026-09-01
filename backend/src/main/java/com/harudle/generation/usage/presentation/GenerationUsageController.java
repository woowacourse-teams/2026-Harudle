package com.harudle.generation.usage.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.generation.usage.domain.GenerationUsage;
import com.harudle.generation.usage.service.GenerationUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Generation")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me/generation-usage")
class GenerationUsageController {

    private final GenerationUsageService generationUsageService;
    private final AuthenticatedUserIdResolver authenticatedUserIdResolver;

    GenerationUsageController(
            GenerationUsageService generationUsageService,
            AuthenticatedUserIdResolver authenticatedUserIdResolver
    ) {
        this.generationUsageService = generationUsageService;
        this.authenticatedUserIdResolver = authenticatedUserIdResolver;
    }

    @Operation(
            summary = "오늘 생성 사용량 조회",
            description = "KST 기준 오늘의 이미지 생성 사용량과 남은 횟수를 조회합니다."
    )
    @GetMapping
    GenerationUsageResponse getTodayUsage(Authentication authentication) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        GenerationUsage usage = generationUsageService.getTodayUsage(userId);
        return GenerationUsageResponse.from(usage);
    }
}
