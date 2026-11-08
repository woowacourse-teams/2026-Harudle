package com.harudle.admin.presentation;

import com.harudle.admin.presentation.dto.AdminReportDetailResponse;
import com.harudle.admin.presentation.dto.AdminReportPageResponse;
import com.harudle.admin.presentation.dto.AdminReportReviewRequest;
import com.harudle.admin.presentation.dto.AdminReportReviewResponse;
import com.harudle.admin.query.AdminReportStatus;
import com.harudle.admin.service.AdminReportQueryService;
import com.harudle.admin.service.AdminReportReviewService;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/reports")
class AdminReportController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    private final AdminReportQueryService queries;
    private final AdminReportReviewService reviews;
    private final AuthenticatedUserIdResolver userIds;
    private final ImageUrlProvider imageUrls;

    AdminReportController(AdminReportQueryService queries, AdminReportReviewService reviews,
            AuthenticatedUserIdResolver userIds, ImageUrlProvider imageUrls) {
        this.queries = queries;
        this.reviews = reviews;
        this.userIds = userIds;
        this.imageUrls = imageUrls;
    }

    @GetMapping
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.FORBIDDEN})
    ResponseEntity<AdminReportPageResponse> search(
            @RequestParam(defaultValue = "RECEIVED") @NotNull AdminReportStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(AdminReportPageResponse.from(queries.search(status, page, size), page, size));
    }

    @GetMapping("/{id}")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.FORBIDDEN,
            ErrorType.ADMIN_REPORT_NOT_FOUND})
    ResponseEntity<AdminReportDetailResponse> findDetail(@PathVariable UUID id) {
        var detail = queries.findDetail(id);
        var target = detail.target();
        var image = target.imageObjectKey() == null ? null
                : imageUrls.forRead().createAccessUrl(target.imageObjectKey());
        var response = new AdminReportDetailResponse(detail.report(),
                AdminReportDetailResponse.TargetResponse.from(target,
                        image == null ? null : image.url(), image == null ? null : image.expiresAt()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @PutMapping("/{id}/review")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.FORBIDDEN,
            ErrorType.ADMIN_REPORT_NOT_FOUND, ErrorType.ADMIN_REPORT_REVIEW_CONFLICT,
            ErrorType.INVALID_ADMIN_REPORT_REVIEW})
    ResponseEntity<AdminReportReviewResponse> review(Authentication authentication, @PathVariable UUID id,
            @Valid @RequestBody AdminReportReviewRequest request) {
        var result = reviews.review(userIds.resolve(authentication), id, request.toReview());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AdminReportReviewResponse.from(result));
    }
}
