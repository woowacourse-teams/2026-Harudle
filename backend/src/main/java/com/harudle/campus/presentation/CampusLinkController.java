package com.harudle.campus.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.campus.application.CampusLinkService;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Campus", description = "우테코 캠퍼스 로그인 연결")
@RestController
@RequestMapping("/api/v1/campus/link-codes")
public class CampusLinkController {

    private static final String BASIC_PREFIX = "Basic ";
    private static final String CLIENT_ID = "campus";

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
            summary = "우테코 캠퍼스 연결 코드 발급",
            description = "로그인한 회원에게 60초 동안 한 번 쓸 수 있는 연결 코드를 발급합니다. "
                    + "프론트엔드는 응답의 redirectUri에 code와 state를 붙여 이동합니다."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiErrorResponses({
            ErrorType.UNAUTHORIZED,
            ErrorType.VALIDATION_ERROR,
            ErrorType.INVALID_CURRENT_USER,
            ErrorType.TERMS_ACCEPTANCE_REQUIRED,
            ErrorType.ACCOUNT_SUSPENDED,
            ErrorType.PUBLIC_PROFILE_NOT_ASSIGNED,
            ErrorType.CAMPUS_LINK_DISABLED
    })
    @PostMapping
    public ResponseEntity<CampusLinkCodeResponse> issue(
            Authentication authentication,
            @Valid @RequestBody CampusLinkCodeRequest request
    ) {
        CampusLinkCodeResponse response = CampusLinkCodeResponse.from(
                service.issue(userIdResolver.resolve(authentication), request.state(), clock.instant())
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @Operation(
            summary = "우테코 캠퍼스 연결 코드 교환",
            description = "캠퍼스 서버 전용입니다. Authorization: Basic base64(campus:<비밀값>)로 인증하고, "
                    + "연결 코드를 한 번 교환해 회원 ID · 닉네임 · 프로필 사진을 받습니다."
    )
    @ApiErrorResponses({
            ErrorType.VALIDATION_ERROR,
            ErrorType.INVALID_CAMPUS_CLIENT,
            ErrorType.INVALID_CAMPUS_LINK_CODE,
            ErrorType.CAMPUS_LINK_DISABLED
    })
    @PostMapping("/exchange")
    public ResponseEntity<CampusLinkProfileResponse> exchange(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CampusLinkExchangeRequest request
    ) {
        CampusLinkProfileResponse response = CampusLinkProfileResponse.from(
                service.exchange(clientSecret(authorization), request.code(), request.state(), clock.instant())
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    // "Basic base64(campus:<비밀값>)"에서 비밀값만 꺼낸다. 형식이 다르면 null(인증 실패로 처리)
    private String clientSecret(String authorization) {
        if (authorization == null || !authorization.startsWith(BASIC_PREFIX)) {
            return null;
        }
        String decoded = decodeBase64(authorization.substring(BASIC_PREFIX.length()).trim());
        String prefix = CLIENT_ID + ":";
        if (decoded == null || !decoded.startsWith(prefix)) {
            return null;
        }
        return decoded.substring(prefix.length());
    }

    private String decodeBase64(String value) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
