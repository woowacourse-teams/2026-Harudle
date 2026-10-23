package com.harudle.generation.diary.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.generation.diary.service.DiaryGenerationStatusQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Generation")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/diary-generations")
class DiaryGenerationController {

    private final DiaryGenerationStatusQueryService queryService;
    private final AuthenticatedUserIdResolver userIdResolver;

    DiaryGenerationController(DiaryGenerationStatusQueryService queryService, AuthenticatedUserIdResolver userIdResolver) {
        this.queryService = queryService;
        this.userIdResolver = userIdResolver;
    }

    @Operation(summary = "일기 생성 상태 조회", description = "본인의 생성 상태를 조회합니다. 성공 시 일기 ID, 실패 시 오류 코드를 반환합니다.")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.GENERATION_NOT_FOUND})
    @GetMapping("/{generationId}")
    ResponseEntity<DiaryGenerationStatusResponse> getStatus(Authentication authentication, @PathVariable UUID generationId) {
        UUID userId = userIdResolver.resolve(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(DiaryGenerationStatusResponse.from(queryService.getStatus(userId, generationId)));
    }
}
