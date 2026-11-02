package com.harudle.block.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.block.service.BlockService;
import com.harudle.block.service.dto.BlockPageResult;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.common.validation.CanonicalUuidParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/blocks")
@Tag(name = "Block", description = "본인이 저장한 회원 차단 관리")
@SecurityRequirement(name = "bearerAuth")
class BlockController {
    private final BlockService service;
    private final AuthenticatedUserIdResolver userIdResolver;

    BlockController(BlockService service, AuthenticatedUserIdResolver userIdResolver) {
        this.service = service;
        this.userIdResolver = userIdResolver;
    }

    @Operation(summary = "회원 차단", description = "본인 방향만 저장합니다. 로그인한 두 회원은 어느 방향의 차단이든 "
            + "서로의 피드와 댓글을 보지 못하며 새 접촉을 할 수 없습니다. 익명 공개 조회는 유지합니다. "
            + "같은 대상의 반복 요청은 최초 차단 시각을 유지합니다. 상대가 차단했는지는 반환하지 않습니다.")
    @ApiResponse(responseCode = "204", description = "차단 완료 또는 이미 본인이 차단한 대상")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.INVALID_BLOCK, ErrorType.BLOCK_TARGET_NOT_FOUND,
            ErrorType.UNAUTHORIZED, ErrorType.INVALID_CSRF_TOKEN, ErrorType.DATABASE_LOCK_TIMEOUT})
    @PutMapping("/{userId}")
    ResponseEntity<Void> block(
            Authentication authentication,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String userId
    ) {
        service.block(userIdResolver.resolve(authentication), targetUserId(userId));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Operation(summary = "본인 차단 해제", description = "본인이 저장한 방향만 해제합니다. 대상이 탈퇴했거나 "
            + "이미 해제한 경우도 동일하게 응답하며, 상대 방향의 차단 여부는 알리지 않습니다.")
    @ApiResponse(responseCode = "204", description = "본인 방향의 차단 해제 완료")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.INVALID_BLOCK,
            ErrorType.UNAUTHORIZED, ErrorType.INVALID_CSRF_TOKEN, ErrorType.DATABASE_LOCK_TIMEOUT})
    @DeleteMapping("/{userId}")
    ResponseEntity<Void> unblock(
            Authentication authentication,
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String userId
    ) {
        service.unblock(userIdResolver.resolve(authentication), targetUserId(userId));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Operation(summary = "본인이 차단한 회원 목록", description = "본인이 저장한 차단만 최신순으로 반환합니다. "
            + "상대의 차단 여부는 포함하지 않습니다. 탈퇴한 대상도 해제할 수 있도록 남겨 둡니다.")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.INVALID_CURSOR,
            ErrorType.UNAUTHORIZED, ErrorType.BLOCK_UNAVAILABLE})
    @GetMapping
    ResponseEntity<BlockPageResult> list(Authentication authentication,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(userIdResolver.resolve(authentication), cursor, size));
    }

    private static UUID targetUserId(String userId) {
        return CanonicalUuidParser.parse(userId)
                .orElseThrow(() -> new ConstraintViolationException("회원 ID의 형식이 올바르지 않습니다.", Set.of()));
    }
}
