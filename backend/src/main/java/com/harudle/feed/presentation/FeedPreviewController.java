package com.harudle.feed.presentation;

import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.feed.configuration.FeedPreviewUrls;
import com.harudle.feed.service.FeedPreviewService;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Feed")
@RestController
@RequestMapping(FeedPreviewUrls.BASE_PATH)
class FeedPreviewController {

    private final FeedPreviewService previews;
    private final FeedPreviewUrls urls;

    FeedPreviewController(FeedPreviewService previews, FeedPreviewUrls urls) {
        this.previews = previews;
        this.urls = urls;
    }

    @Operation(summary = "공개 피드 공유 미리보기", description = "로그인 없이 현재 공개된 피드의 OG 태그를 HTML로 반환합니다. "
            + "브라우저에서는 기존 피드 화면으로 이동하며, URL 쿼리의 유입 정보는 유지합니다. 일기 원문은 제공하지 않습니다.")
    @ApiResponse(responseCode = "200", description = "피드별 공유 HTML")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.FEED_NOT_FOUND, ErrorType.FEED_UNAVAILABLE})
    @GetMapping(value = "/{feedId}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page(@PathVariable UUID feedId) {
        String html = FeedPreviewHtml.render(previews.metadata(feedId), urls.shareUrl(feedId), urls.imageUrl(feedId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", FeedPreviewHtml.contentSecurityPolicy())
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8)).body(html);
    }

    @Operation(summary = "공개 피드 미리보기 이미지", description = "URL 자체에 만료 시간이 없는 OG 전용 JPEG를 반환합니다. "
            + "매 요청마다 익명 공개 범위를 검사하므로 삭제·탈퇴·관리자 숨김된 피드는 제공하지 않습니다. "
            + "S3/R2 주소와 개인 일기 원문을 노출하지 않으며, 기존 화면의 서명 URL 정책을 바꾸지 않습니다.")
    @ApiResponse(responseCode = "200", description = "1200×630 JPEG 이미지")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.FEED_NOT_FOUND, ErrorType.FEED_UNAVAILABLE,
            ErrorType.IMAGE_STORAGE_ERROR, ErrorType.FEED_PREVIEW_CAPACITY_EXCEEDED})
    @GetMapping(value = "/{feedId}/image", produces = MediaType.IMAGE_JPEG_VALUE)
    ResponseEntity<Resource> image(@PathVariable UUID feedId) {
        ReferenceImage image = previews.image(feedId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG).body(image.resource());
    }

    @Operation(hidden = true)
    @RequestMapping(value = "/{feedId}/image", method = RequestMethod.HEAD, produces = MediaType.IMAGE_JPEG_VALUE)
    ResponseEntity<Void> imageHead(@PathVariable UUID feedId) {
        // GET과 같은 공개 여부·이미지 제공 경로를 사용한다. 완성된 JPEG는 내부 캐시에서 재사용할 수 있다.
        ResponseEntity<Resource> image = image(feedId);
        return ResponseEntity.ok().headers(image.getHeaders()).build();
    }
}
