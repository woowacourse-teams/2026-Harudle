package com.harudle.auth.presentation;

import com.harudle.auth.application.CurrentUserResult;
import com.harudle.auth.application.CurrentUserService;
import com.harudle.auth.application.InvalidCurrentUserException;
import com.harudle.auth.application.NicknameChangeService;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me")
public class CurrentUserController {

    private final CurrentUserService currentUserService;
    private final NicknameChangeService nicknameChangeService;
    private final ProblemDetailFactory problemDetailFactory;

    public CurrentUserController(
            CurrentUserService currentUserService,
            NicknameChangeService nicknameChangeService,
            ProblemDetailFactory problemDetailFactory
    ) {
        this.currentUserService = currentUserService;
        this.nicknameChangeService = nicknameChangeService;
        this.problemDetailFactory = problemDetailFactory;
    }

    @Operation(
            summary = "내 프로필 조회",
            description = "인증된 사용자의 프로필과 연결된 OAuth Provider를 조회합니다."
    )
    @ApiErrorResponses({
            ErrorType.UNAUTHORIZED,
            ErrorType.INVALID_CURRENT_USER
    })
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

    @Operation(summary = "내 닉네임 변경")
    @ApiErrorResponses({
            ErrorType.UNAUTHORIZED,
            ErrorType.INVALID_CURRENT_USER,
            ErrorType.INVALID_CSRF_TOKEN,
            ErrorType.VALIDATION_ERROR,
            ErrorType.NICKNAME_ALREADY_IN_USE,
            ErrorType.PUBLIC_PROFILE_NOT_ASSIGNED
    })
    @PatchMapping("/nickname")
    public ResponseEntity<Void> changeNickname(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangeNicknameRequest request
    ) {
        nicknameChangeService.change(extractUserId(jwt), request.nickname());

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @ExceptionHandler(InvalidCurrentUserException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCurrentUser(HttpServletRequest request) {
        return ResponseEntity
                .status(ErrorType.INVALID_CURRENT_USER.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .body(problemDetailFactory.create(ErrorType.INVALID_CURRENT_USER, request));
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
