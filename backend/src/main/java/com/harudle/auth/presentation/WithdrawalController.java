package com.harudle.auth.presentation;

import com.harudle.auth.application.WithdrawalService;
import com.harudle.auth.application.InvalidCurrentUserException;
import com.harudle.auth.application.WithdrawalDatabaseService.WithdrawalGenerationInProgressException;
import com.harudle.auth.infrastructure.oauth.GoogleWithdrawalAuthorizationState;
import com.harudle.auth.infrastructure.oauth.SocialUnlinkResult;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import com.harudle.common.validation.CanonicalUuidParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/me/withdrawal")
public class WithdrawalController {

    private final WithdrawalService withdrawal;
    private final GoogleWithdrawalAuthorizationState state;
    private final RefreshTokenCookieWriter cookies;

    public WithdrawalController(WithdrawalService withdrawal, GoogleWithdrawalAuthorizationState state,
            RefreshTokenCookieWriter cookies) {
        this.withdrawal = withdrawal;
        this.state = state;
        this.cookies = cookies;
    }

    @PostMapping
    public ResponseEntity<WithdrawalResponse> begin(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request,
            HttpServletResponse response) {
        UUID userId = CanonicalUuidParser.parse(jwt.getSubject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        SocialUnlinkResult result;
        try {
            result = withdrawal.begin(userId);
        } catch (InvalidCurrentUserException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        } catch (WithdrawalGenerationInProgressException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "실패했어요!");
        }
        WithdrawalResponse body;
        if (result == SocialUnlinkResult.GOOGLE_AUTHENTICATION_REQUIRED) {
            String requestId = state.begin(request.getSession(true), userId);
            body = new WithdrawalResponse("AUTHENTICATION_REQUIRED",
                    "/oauth2/authorization/google?withdrawal=" + requestId, false);
        } else {
            cookies.clear(response);
            body = new WithdrawalResponse("COMPLETED", null,
                    result == SocialUnlinkResult.APPLE_TOKEN_UNAVAILABLE);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(body);
    }

    public record WithdrawalResponse(String status, @Nullable String authorizationUrl, boolean appleManualUnlinkRequired) {}
}
