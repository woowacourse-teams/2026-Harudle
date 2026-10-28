package com.harudle.legal.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.legal.application.TermsAcceptanceService;
import com.harudle.legal.application.TermsAcceptanceStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/agreements")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Legal", description = "이용약관 열람과 명시적 수락")
class TermsAcceptanceController {
    private final TermsAcceptanceService service;
    private final AuthenticatedUserIdResolver users;

    TermsAcceptanceController(TermsAcceptanceService service, AuthenticatedUserIdResolver users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "본인의 약관 수락 상태 조회", description = "문서 참조는 현재 열람할 버전이며, 수락 시각은 서비스 접근에 "
            + "인정되는 실제 기록의 최초 시각입니다. 공지성 개정으로 이전 버전의 수락이 유지될 수 있습니다.")
    @ApiErrorResponses({ErrorType.UNAUTHORIZED, ErrorType.ACCOUNT_SUSPENDED})
    ResponseEntity<TermsAcceptanceStatus> status(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.status(users.resolve(authentication)));
    }

    @PutMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "표시된 버전의 약관 수락", description = "회원 ID와 수락 시각은 서버가 결정합니다. "
            + "같은 버전을 다시 제출해도 최초 수락 시각을 보존합니다. 개인정보처리방침은 제시한 고지문 참조로 기록하며 "
            + "개인정보 처리 전체에 대한 일괄 동의로 취급하지 않습니다.")
    @ApiErrorResponses({ErrorType.VALIDATION_ERROR, ErrorType.INVALID_TERMS_ACCEPTANCE,
            ErrorType.UNAUTHORIZED,
            ErrorType.ACCOUNT_SUSPENDED, ErrorType.LEGAL_DOCUMENT_CHANGED})
    ResponseEntity<TermsAcceptanceStatus> accept(
            Authentication authentication, @Valid @RequestBody AcceptTermsRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.accept(users.resolve(authentication), request.toCommand()));
    }
}
