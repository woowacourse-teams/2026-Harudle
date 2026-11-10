package com.harudle.recovery;

import com.harudle.auth.application.AccountSuspendedException;
import com.harudle.auth.application.AuthService;
import com.harudle.auth.application.InactiveUserException;
import com.harudle.auth.application.InvalidRefreshTokenException;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieReader;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import com.harudle.auth.presentation.RefreshTokenResponse;
import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reuses the normal refresh cookie and rotation policy while the normal application is unavailable. */
@RestController
@Profile("image-recovery")
class ImageRecoveryAuthController {
    private final AuthService auth;
    private final RefreshTokenCookieReader reader;
    private final RefreshTokenCookieWriter writer;
    private final ProblemDetailFactory problems;
    private final Clock clock;

    ImageRecoveryAuthController(AuthService auth, RefreshTokenCookieReader reader,
            RefreshTokenCookieWriter writer, ProblemDetailFactory problems, @Qualifier("authClock") Clock clock) {
        this.auth = auth;
        this.reader = reader;
        this.writer = writer;
        this.problems = problems;
        this.clock = clock;
    }

    @PostMapping("/api/v1/auth/refresh")
    ResponseEntity<RefreshTokenResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String rawToken = reader.read(request).orElseThrow(InvalidRefreshTokenException::new);
        var refreshed = auth.refresh(rawToken, now);
        writer.write(response, refreshed.refreshToken());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(RefreshTokenResponse.from(refreshed.accessToken(), now));
    }

    @ExceptionHandler(AccountSuspendedException.class)
    ResponseEntity<ProblemDetail> suspended(HttpServletRequest request, HttpServletResponse response) {
        return reject(ErrorType.ACCOUNT_SUSPENDED, request, response);
    }

    @ExceptionHandler({InvalidRefreshTokenException.class, InactiveUserException.class})
    ResponseEntity<ProblemDetail> invalid(HttpServletRequest request, HttpServletResponse response) {
        return reject(ErrorType.INVALID_REFRESH_TOKEN, request, response);
    }

    private ResponseEntity<ProblemDetail> reject(ErrorType error, HttpServletRequest request,
            HttpServletResponse response) {
        writer.clear(response);
        return ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(problems.create(error, request));
    }
}
