package com.harudle.auth.presentation;

import com.harudle.auth.application.CurrentUserResult;
import com.harudle.auth.application.CurrentUserService;
import com.harudle.auth.application.InvalidCurrentUserException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class CurrentUserController {

    private final CurrentUserService currentUserService;

    public CurrentUserController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public ResponseEntity<CurrentUserResponse> find(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = extractUserId(jwt);
        CurrentUserResult result = currentUserService.find(userId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CurrentUserResponse.from(result));
    }

    @ExceptionHandler(InvalidCurrentUserException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCurrentUser(
            HttpServletRequest request
    ) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "현재 로그인 사용자를 확인할 수 없습니다."
        );
        problemDetail.setTitle(HttpStatus.UNAUTHORIZED.getReasonPhrase());
        problemDetail.setType(URI.create("https://api.harudle.example/problems/invalid-current-user"));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setProperty("code", "INVALID_CURRENT_USER");

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .body(problemDetail);
    }

    private UUID extractUserId(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt는 필수입니다.");
        String subject = jwt.getSubject();

        if (subject == null || subject.isBlank()) {
            throw new InvalidCurrentUserException();
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw new InvalidCurrentUserException();
        }
    }
}
