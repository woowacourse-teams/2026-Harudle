package com.harudle.push.presentation;

import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.validation.CanonicalUuidParser;
import com.harudle.push.infrastructure.JpaPushRegistrationService;
import com.harudle.push.infrastructure.JpaPushRegistrationService.InvalidPushSession;
import com.harudle.push.infrastructure.JpaPushRegistrationService.Registration;
import com.harudle.push.infrastructure.JpaPushRegistrationService.RegistrationConflict;
import com.harudle.push.infrastructure.JpaPushRegistrationService.StaleRegistration;
import com.harudle.push.infrastructure.JpaPushRegistrationService.StalePushState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Push")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me/push-registrations")
public class PushRegistrationController {

    private final JpaPushRegistrationService registrations;
    private final ProblemDetailFactory problems;

    public PushRegistrationController(JpaPushRegistrationService registrations, ProblemDetailFactory problems) {
        this.registrations = registrations;
        this.problems = problems;
    }

    @Operation(summary = "현재 기기의 푸시 등록 또는 갱신")
    @ApiErrorResponses({ErrorType.UNAUTHORIZED, ErrorType.INVALID_PUSH_SESSION,
            ErrorType.VALIDATION_ERROR, ErrorType.PUSH_REGISTRATION_CONFLICT,
            ErrorType.STALE_PUSH_REGISTRATION, ErrorType.STALE_PUSH_STATE})
    @PutMapping("/{deviceId}")
    public ResponseEntity<Registration> register(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID deviceId,
                                                  @Valid @RequestBody RegistrationRequest request) {
        Registration result = registrations.register(userId(jwt), sessionId(jwt), deviceId,
                request.fid(), request.registrationId(), request.version(), request.stateVersion());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @Operation(summary = "현재 기기의 푸시 등록 해제")
    @ApiErrorResponses({ErrorType.UNAUTHORIZED, ErrorType.INVALID_PUSH_SESSION,
            ErrorType.VALIDATION_ERROR, ErrorType.STALE_PUSH_STATE})
    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
                                       @RequestParam @PositiveOrZero long stateVersion) {
        long currentVersion = registrations.revoke(userId(jwt), sessionId(jwt), deviceId, stateVersion);
        return ResponseEntity.noContent().header("Push-State-Version", Long.toString(currentVersion))
                .cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(InvalidPushSession.class)
    public ResponseEntity<ProblemDetail> invalidSession(HttpServletRequest request) {
        return problem(ErrorType.INVALID_PUSH_SESSION, request);
    }

    @ExceptionHandler(RegistrationConflict.class)
    public ResponseEntity<ProblemDetail> registrationConflict(HttpServletRequest request) {
        return problem(ErrorType.PUSH_REGISTRATION_CONFLICT, request);
    }

    @ExceptionHandler(StaleRegistration.class)
    public ResponseEntity<ProblemDetail> staleRegistration(StaleRegistration exception,
                                                           HttpServletRequest request) {
        ProblemDetail detail = problems.create(ErrorType.STALE_PUSH_REGISTRATION, request);
        detail.setProperty("current", exception.current());
        return response(ErrorType.STALE_PUSH_REGISTRATION, detail);
    }

    @ExceptionHandler(StalePushState.class)
    public ResponseEntity<ProblemDetail> stalePushState(StalePushState exception, HttpServletRequest request) {
        ProblemDetail detail = problems.create(ErrorType.STALE_PUSH_STATE, request);
        detail.setProperty("currentStateVersion", exception.stateVersion());
        detail.setProperty("enabled", exception.enabled());
        return response(ErrorType.STALE_PUSH_STATE, detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(HttpServletRequest request) {
        return problem(ErrorType.VALIDATION_ERROR, request);
    }

    private ResponseEntity<ProblemDetail> problem(ErrorType type, HttpServletRequest request) {
        return response(type, problems.create(type, request));
    }

    private ResponseEntity<ProblemDetail> response(ErrorType type, ProblemDetail detail) {
        return ResponseEntity.status(type.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(detail);
    }

    private UUID userId(Jwt jwt) {
        return CanonicalUuidParser.parse(jwt.getSubject()).orElseThrow(InvalidPushSession::new);
    }

    private UUID sessionId(Jwt jwt) {
        return CanonicalUuidParser.parse(jwt.getClaimAsString("sid")).orElseThrow(InvalidPushSession::new);
    }

    public record RegistrationRequest(@NotBlank String fid, UUID registrationId,
                                      @PositiveOrZero Long version,
                                      @NotNull @PositiveOrZero Long stateVersion) { }
}
