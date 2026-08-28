package com.harudle.auth.presentation;

import com.harudle.auth.application.AuthService;
import com.harudle.auth.application.InactiveUserException;
import com.harudle.auth.application.InvalidRefreshTokenException;
import com.harudle.auth.application.RefreshedTokens;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieReader;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.security.LegacyCsrfCookieCleaner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookieReader refreshTokenCookieReader;
    private final RefreshTokenCookieWriter refreshTokenCookieWriter;
    private final CookieCsrfTokenRepository csrfTokenRepository;
    private final ProblemDetailFactory problemDetailFactory;
    private final LegacyCsrfCookieCleaner legacyCsrfCookieCleaner;
    private final Clock clock;

    public AuthController(
            AuthService authService,
            RefreshTokenCookieReader refreshTokenCookieReader,
            RefreshTokenCookieWriter refreshTokenCookieWriter,
            CookieCsrfTokenRepository csrfTokenRepository,
            ProblemDetailFactory problemDetailFactory,
            LegacyCsrfCookieCleaner legacyCsrfCookieCleaner,
            @Qualifier("authClock")
            Clock authClock
    ) {
        this.authService = authService;
        this.refreshTokenCookieReader = refreshTokenCookieReader;
        this.refreshTokenCookieWriter = refreshTokenCookieWriter;
        this.csrfTokenRepository = csrfTokenRepository;
        this.problemDetailFactory = problemDetailFactory;
        this.legacyCsrfCookieCleaner = legacyCsrfCookieCleaner;
        this.clock = Objects.requireNonNull(authClock, "authClock는 필수입니다.");
    }

    @Operation(
            summary = "CSRF 토큰 발급",
            description = "쿠키 기반 요청에 사용할 CSRF 토큰을 발급합니다."
    )
    @GetMapping("/csrf")
    public ResponseEntity<CsrfTokenResponse> csrf(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        CsrfToken csrfToken = csrfTokenRepository.generateToken(request);
        legacyCsrfCookieCleaner.clear(response);
        csrfTokenRepository.saveToken(csrfToken, request, response);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfTokenResponse(csrfToken.getToken()));
    }

    @Operation(
            summary = "Access Token 재발급",
            description = "Refresh Token 쿠키를 검증하고 Access Token과 회전된 Refresh Token을 발급합니다."
    )
    @PostMapping("/refresh")
    public ResponseEntity<RefreshTokenResponse> refresh(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        String rawToken = refreshTokenCookieReader.read(request)
                .orElseThrow(InvalidRefreshTokenException::new);
        RefreshedTokens refreshedTokens = authService.refresh(rawToken, now);
        refreshTokenCookieWriter.write(
                response,
                refreshedTokens.refreshToken()
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RefreshTokenResponse.from(refreshedTokens.accessToken(), now));
    }

    @Operation(
            summary = "로그아웃",
            description = "Refresh Token 쿠키로 현재 세션을 폐기하고 쿠키를 삭제합니다."
    )
    @ApiResponse(responseCode = "204", description = "로그아웃 완료")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        refreshTokenCookieReader.read(request)
                .ifPresent(rawToken -> authService.logout(rawToken, now));
        refreshTokenCookieWriter.clear(response);

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @ExceptionHandler({InvalidRefreshTokenException.class, InactiveUserException.class})
    public ResponseEntity<ProblemDetail> handleInvalidRefreshToken(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        refreshTokenCookieWriter.clear(response);

        return ResponseEntity
                .status(ErrorType.INVALID_REFRESH_TOKEN.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .body(problemDetailFactory.create(ErrorType.INVALID_REFRESH_TOKEN, request));
    }

}
