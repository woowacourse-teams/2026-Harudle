package com.harudle.report.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Report", description = "회원의 사용자·공개 콘텐츠 신고 접수")
class ReportController {

    private final ReportService service;
    private final AuthenticatedUserIdResolver userIdResolver;

    ReportController(ReportService service, AuthenticatedUserIdResolver userIdResolver) {
        this.service = service;
        this.userIdResolver = userIdResolver;
    }

    @Operation(summary = "사용자·피드·댓글 신고 접수", description = "본인과 본인 콘텐츠는 신고할 수 없습니다. "
            + "같은 회원의 같은 대상 신고는 기존 접수를 반환하고 최초 사유를 보존합니다. "
            + "접수 후 대상이 삭제돼도 접수는 보존하며 자동 숨김·제재는 수행하지 않습니다. "
            + "신고자·사유·상세 내용은 응답하지 않으며 일반 사용자용 신고 조회 API는 제공하지 않습니다.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "201", description = "새 신고 접수")
    @ApiResponse(responseCode = "200", description = "이미 접수된 본인 신고")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.INVALID_REPORT, ErrorType.UNAUTHORIZED,
            ErrorType.INVALID_CSRF_TOKEN, ErrorType.REPORT_TARGET_NOT_FOUND})
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReportResponse> submit(
            Authentication authentication, @Valid @RequestBody SubmitReportRequest request
    ) {
        var receipt = service.submit(userIdResolver.resolve(authentication), request.toSubmission());
        var response = receipt.duplicate() ? ResponseEntity.ok()
                : ResponseEntity.created(URI.create("/api/v1/reports/" + receipt.id()));
        return response.cacheControl(CacheControl.noStore()).body(ReportResponse.from(receipt));
    }
}
