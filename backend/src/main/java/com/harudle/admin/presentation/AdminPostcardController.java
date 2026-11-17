package com.harudle.admin.presentation;

import com.harudle.admin.presentation.dto.AdminPostcardImageResponse;
import com.harudle.admin.service.AdminPostcardImageService;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 생성 이력에서 고른 만화를 엽서 프레임과 합성해 인쇄할 수 있도록 원본 이미지 주소를 발급한다. */
@RestController
@RequestMapping("/api/v1/admin/generations")
class AdminPostcardController {

    private final AdminPostcardImageService postcardImages;
    private final ImageUrlProvider imageUrls;

    AdminPostcardController(AdminPostcardImageService postcardImages, ImageUrlProvider imageUrls) {
        this.postcardImages = postcardImages;
        this.imageUrls = imageUrls;
    }

    @GetMapping("/{generationId}/postcard-image")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.FORBIDDEN,
            ErrorType.GENERATION_NOT_FOUND})
    ResponseEntity<AdminPostcardImageResponse> findPostcardImage(@PathVariable UUID generationId) {
        var image = postcardImages.find(generationId);
        var accessUrl = imageUrls.forRead().createAccessUrl(image.imageObjectKey());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AdminPostcardImageResponse(accessUrl.url(), accessUrl.expiresAt(), image.title()));
    }
}
