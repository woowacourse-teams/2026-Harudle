package com.harudle.share.controller;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.share.controller.dto.ShareLinkResponse;
import com.harudle.share.service.ShareLinkCreationResult;
import com.harudle.share.service.ShareLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Share")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping(ShareLinkController.BASE_PATH)
class ShareLinkController {

    static final String BASE_PATH = "/api/v1/diaries";

    private final ShareLinkService shareLinkService;
    private final AuthenticatedUserIdResolver authenticatedUserIdResolver;
    private final ShareLinkResponseAssembler responseAssembler;

    ShareLinkController(
            ShareLinkService shareLinkService,
            AuthenticatedUserIdResolver authenticatedUserIdResolver,
            ShareLinkResponseAssembler responseAssembler
    ) {
        this.shareLinkService = shareLinkService;
        this.authenticatedUserIdResolver = authenticatedUserIdResolver;
        this.responseAssembler = responseAssembler;
    }

    @Operation(
            summary = "공유 링크 생성 또는 조회",
            description = "성공적으로 생성된 일기의 공유 링크를 생성하거나 기존 링크를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "새 공유 링크 생성 완료"),
            @ApiResponse(responseCode = "200", description = "기존 공유 링크 반환")
    })
    @PutMapping("/{diaryId}/share-link")
    ResponseEntity<ShareLinkResponse> createOrGet(
            Authentication authentication,
            @PathVariable UUID diaryId
    ) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        ShareLinkCreationResult result = shareLinkService.createOrGet(userId, diaryId);
        ShareLinkResponse response = responseAssembler.toResponse(result);
        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
        return ResponseEntity.ok(response);
    }
}
