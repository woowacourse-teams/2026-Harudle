package com.harudle.feed.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.feed.service.FeedPublicationService;
import com.harudle.feed.service.FeedQueryService;
import com.harudle.feed.service.dto.FeedResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Feed")
@RestController
@RequestMapping(FeedController.BASE_PATH)
class FeedController {

    static final String BASE_PATH = "/api/v1/feeds";
    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final FeedPublicationService publicationService;
    private final FeedQueryService queryService;
    private final AuthenticatedUserIdResolver userIds;
    private final FeedResponseAssembler responseAssembler;

    FeedController(
            FeedPublicationService publicationService,
            FeedQueryService queryService,
            AuthenticatedUserIdResolver userIds,
            FeedResponseAssembler responseAssembler
    ) {
        this.publicationService = publicationService;
        this.queryService = queryService;
        this.userIds = userIds;
        this.responseAssembler = responseAssembler;
    }

    @Operation(summary = "피드 게시", description = "생성 완료된 본인 일기의 만화 이미지를 선택한 카테고리에 게시합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "피드 게시 완료")
    @ApiErrorResponses({
            ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.FORBIDDEN,
            ErrorType.INVALID_CSRF_TOKEN, ErrorType.DIARY_NOT_FOUND, ErrorType.CATEGORY_NOT_FOUND,
            ErrorType.DIARY_NOT_PUBLISHABLE, ErrorType.DIARY_ALREADY_PUBLISHED, ErrorType.CATEGORY_INACTIVE,
            ErrorType.FEED_UNAVAILABLE, ErrorType.IMAGE_STORAGE_ERROR
    })
    @PostMapping
    ResponseEntity<FeedResponse> publish(Authentication authentication, @Valid @RequestBody PublishFeedRequest request) {
        UUID actorId = userIds.resolve(authentication);
        FeedResult result = publicationService.publish(actorId, request.diaryId(), request.categoryId());
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + result.id()))
                .body(responseAssembler.toResponse(result));
    }

    @Operation(
            summary = "공개 피드 상세 조회",
            description = "로그인 없이 조회할 수 있습니다. 유효한 Bearer 토큰이 있으면 내 좋아요와 소유 여부를 반환합니다."
    )
    @ApiErrorResponses({
            ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.FEED_NOT_FOUND,
            ErrorType.FEED_UNAVAILABLE, ErrorType.IMAGE_STORAGE_ERROR
    })
    @GetMapping("/{feedId}")
    FeedResponse getDetail(
            Authentication authentication,
            @Parameter(schema = @Schema(type = "string", format = "uuid"))
            @PathVariable @Pattern(regexp = UUID_PATTERN) String feedId
    ) {
        UUID viewerId = userIds.resolveOptional(authentication).orElse(null);
        return responseAssembler.toResponse(queryService.getDetail(viewerId, UUID.fromString(feedId)));
    }
}
