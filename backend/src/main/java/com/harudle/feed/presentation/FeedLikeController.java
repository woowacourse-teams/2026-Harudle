package com.harudle.feed.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.feed.service.FeedLikeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Feed", description = "공개 피드 반응")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(value = "/api/v1/feeds/{feedId}/like", produces = MediaType.APPLICATION_JSON_VALUE)
class FeedLikeController {

    private final FeedLikeService feedLikeService;
    private final AuthenticatedUserIdResolver authenticatedUserIdResolver;

    FeedLikeController(
            FeedLikeService feedLikeService,
            AuthenticatedUserIdResolver authenticatedUserIdResolver
    ) {
        this.feedLikeService = feedLikeService;
        this.authenticatedUserIdResolver = authenticatedUserIdResolver;
    }

    @Operation(summary = "피드 좋아요 추가", description = "로그인 회원의 좋아요를 설정합니다. 반복 요청은 중복 추가하지 않습니다.")
    @ApiResponse(responseCode = "200", description = "좋아요 상태와 현재 좋아요 수")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.INVALID_CSRF_TOKEN,
            ErrorType.FEED_NOT_FOUND})
    @PutMapping
    ResponseEntity<FeedLikeResponse> like(Authentication authentication, @PathVariable UUID feedId) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(FeedLikeResponse.from(feedLikeService.like(userId, feedId)));
    }

    @Operation(summary = "피드 좋아요 취소", description = "로그인 회원의 좋아요를 취소합니다. 이미 취소한 요청도 성공합니다.")
    @ApiResponse(responseCode = "200", description = "좋아요 상태와 현재 좋아요 수")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.INVALID_CSRF_TOKEN,
            ErrorType.FEED_NOT_FOUND})
    @DeleteMapping
    ResponseEntity<FeedLikeResponse> unlike(Authentication authentication, @PathVariable UUID feedId) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(FeedLikeResponse.from(feedLikeService.unlike(userId, feedId)));
    }
}
