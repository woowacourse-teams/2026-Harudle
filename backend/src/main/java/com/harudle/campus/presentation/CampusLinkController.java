package com.harudle.campus.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.campus.application.CampusLinkService;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 우테코 캠퍼스 연결 토큰 발급. 기존 API 보안 체인의 "/api/v1/** 인증 필요" 규칙과 약관 확인을 그대로 따른다.
 * 기능이 꺼져 있으면 이 API 자체를 등록하지 않는다.
 */
@Tag(name = "Campus", description = "우테코 캠퍼스 로그인 연결")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/campus/link-tokens")
@ConditionalOnProperty(prefix = "harudle.campus", name = "enabled", havingValue = "true")
public class CampusLinkController {

    private final CampusLinkService service;
    private final AuthenticatedUserIdResolver userIdResolver;
    private final Clock clock;

    public CampusLinkController(
            CampusLinkService service,
            AuthenticatedUserIdResolver userIdResolver,
            @Qualifier("authClock") Clock clock
    ) {
        this.service = service;
        this.userIdResolver = userIdResolver;
        this.clock = clock;
    }

    @Operation(
            summary = "우테코 캠퍼스 연결 토큰 발급",
            description = "로그인한 회원의 ID · 닉네임 · 프로필 사진을 담은 60초짜리 서명 토큰을 발급합니다. "
                    + "프론트엔드는 응답의 redirectUri에 token과 state를 붙여 이동합니다. 하루들은 토큰을 저장하지 않습니다."
    )
    @ApiErrorResponses({
            ErrorType.UNAUTHORIZED,
            ErrorType.VALIDATION_ERROR,
            ErrorType.INVALID_CURRENT_USER,
            ErrorType.TERMS_ACCEPTANCE_REQUIRED,
            ErrorType.ACCOUNT_SUSPENDED,
            ErrorType.PUBLIC_PROFILE_NOT_ASSIGNED
    })
    @PostMapping
    public ResponseEntity<CampusLinkTokenResponse> issue(
            Authentication authentication,
            @Valid @RequestBody CampusLinkTokenRequest request
    ) {
        CampusLinkTokenResponse response = CampusLinkTokenResponse.from(
                service.issue(userIdResolver.resolve(authentication), request.state(), clock.instant())
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
