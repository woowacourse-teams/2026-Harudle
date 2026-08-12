package com.harudle.share.controller;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.share.controller.dto.ShareLinkResponse;
import com.harudle.share.service.ShareLinkCreationResult;
import com.harudle.share.service.ShareLinkService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
