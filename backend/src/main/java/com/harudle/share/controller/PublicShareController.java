package com.harudle.share.controller;

import com.harudle.share.controller.dto.PublicShareResponse;
import com.harudle.share.service.PublicShareResult;
import com.harudle.share.service.ShareLinkQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Share")
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

    @Operation(
            summary = "공개 공유 결과 조회",
            description = "공유 ID로 공개 가능한 일기 이미지와 제목을 조회합니다. 원문과 사용자 정보는 반환하지 않습니다."
    )
    @GetMapping("/{shareId}")
    PublicShareResponse getPublicShare(@PathVariable UUID shareId) {
        PublicShareResult result = shareLinkQueryService.getPublicShare(shareId);
        return responseAssembler.toResponse(result);
    }
}
