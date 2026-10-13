package com.harudle.comment.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.comment.service.CommentService;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@Tag(name = "Comment", description = "공개 피드 댓글")
@RestController
@RequestMapping("/api/v1")
class CommentController {
    private final CommentService commentService;
    private final AuthenticatedUserIdResolver userIdResolver;
    private final AuthenticationTrustResolver authenticationTrustResolver;

    CommentController(CommentService commentService, AuthenticatedUserIdResolver userIdResolver,
            AuthenticationTrustResolver authenticationTrustResolver) {
        this.commentService = commentService;
        this.userIdResolver = userIdResolver;
        this.authenticationTrustResolver = authenticationTrustResolver;
    }

    @Operation(summary = "댓글 목록 조회", description = "로그인 없이 오래된 댓글순으로 조회합니다. 유효한 Bearer가 있으면 isMine을 반환합니다. "
            + "댓글 작성자가 탈퇴해도 댓글은 유지하며, 작성자는 탈퇴한 회원으로 표시하고 ID·이미지 URL은 null, isMine은 false입니다.")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.FEED_NOT_FOUND,
            ErrorType.INVALID_CURSOR, ErrorType.COMMENT_UNAVAILABLE})
    @GetMapping(value = "/feeds/{feedId}/comments", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CommentPageResponse> list(Authentication authentication, @PathVariable UUID feedId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        UUID viewerId = authenticationTrustResolver.isAuthenticated(authentication) ? userIdResolver.resolve(authentication) : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CommentPageResponse.from(commentService.list(feedId, viewerId, cursor, size)));
    }

    @Operation(summary = "댓글 작성", description = "회원이 공개 피드에 1~100자의 일반 텍스트 댓글을 작성합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "댓글 작성 완료")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.INVALID_CSRF_TOKEN,
            ErrorType.FEED_NOT_FOUND, ErrorType.COMMENT_UNAVAILABLE})
    @PostMapping(value = "/feeds/{feedId}/comments", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CommentResponse> create(Authentication authentication, @PathVariable UUID feedId,
            @Valid @RequestBody CreateCommentRequest request) {
        var comment = commentService.create(userIdResolver.resolve(authentication), feedId, request.content());
        return ResponseEntity.created(URI.create("/api/v1/comments/" + comment.id()))
                .cacheControl(CacheControl.noStore()).body(CommentResponse.from(comment));
    }

    @Operation(summary = "댓글 삭제", description = "본인이 작성한 댓글을 삭제합니다. 삭제된 댓글과 공개 해제된 부모 피드는 404입니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "204", description = "댓글 삭제 완료")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.INVALID_CSRF_TOKEN,
            ErrorType.FORBIDDEN, ErrorType.COMMENT_NOT_FOUND})
    @DeleteMapping("/comments/{commentId}")
    ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID commentId) {
        commentService.delete(userIdResolver.resolve(authentication), commentId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
