package com.harudle.generation.diary.presentation;

import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.generation.diary.service.DiaryGenerationStatusQueryService;
import com.harudle.guest.application.exception.GuestSessionRequiredException;
import com.harudle.guest.infrastructure.cookie.GuestSessionCookieReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Guest")
@RestController
@RequestMapping("/api/v1/guest/diary-generations")
class GuestDiaryGenerationController {

    private final DiaryGenerationStatusQueryService queryService;
    private final GuestSessionCookieReader cookieReader;

    GuestDiaryGenerationController(DiaryGenerationStatusQueryService queryService, GuestSessionCookieReader cookieReader) {
        this.queryService = queryService;
        this.cookieReader = cookieReader;
    }

    @Operation(summary = "게스트 일기 생성 상태 조회", description = "현재 게스트 세션에 연결된 작업의 상태를 조회합니다.")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.GUEST_SESSION_REQUIRED,
            ErrorType.GUEST_SESSION_EXPIRED, ErrorType.GENERATION_NOT_FOUND})
    @GetMapping("/{generationId}")
    ResponseEntity<DiaryGenerationStatusResponse> getStatus(HttpServletRequest request, @PathVariable UUID generationId) {
        String rawToken = cookieReader.read(request).orElseThrow(GuestSessionRequiredException::new);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(DiaryGenerationStatusResponse.from(queryService.getGuestStatus(rawToken, generationId)));
    }
}
