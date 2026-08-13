package com.harudle.share.controller;

import com.harudle.share.controller.dto.PublicShareResponse;
import com.harudle.share.service.PublicShareResult;
import com.harudle.share.service.ShareLinkQueryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/shares")
class PublicShareController {

    private final ShareLinkQueryService shareLinkQueryService;
    private final PublicShareResponseAssembler responseAssembler;

    PublicShareController(
            ShareLinkQueryService shareLinkQueryService,
            PublicShareResponseAssembler responseAssembler
    ) {
        this.shareLinkQueryService = shareLinkQueryService;
        this.responseAssembler = responseAssembler;
    }

    @GetMapping("/{shareId}")
    PublicShareResponse getPublicShare(@PathVariable UUID shareId) {
        PublicShareResult result = shareLinkQueryService.getPublicShare(shareId);
        return responseAssembler.toResponse(result);
    }
}
