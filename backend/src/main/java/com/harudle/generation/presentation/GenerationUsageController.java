package com.harudle.generation.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.service.GenerationUsageService;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping
    GenerationUsageResponse getTodayUsage(Authentication authentication) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        GenerationUsage usage = generationUsageService.getTodayUsage(userId);
        return GenerationUsageResponse.from(usage);
    }
}
