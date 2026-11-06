package com.harudle.diary.presentation;

import com.harudle.auth.application.InvalidCurrentUserException;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.diary.service.GuestDiaryClaimService;
import com.harudle.diary.service.dto.GuestDiaryClaimResult;
import com.harudle.guest.infrastructure.cookie.GuestSessionCookieReader;
import com.harudle.guest.infrastructure.cookie.GuestSessionCookieWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Guest")
@SecurityRequirement(name = "bearerAuth")
@RestController
class GuestDiaryClaimController {

    static final String PATH = "/api/v1/guest/diary-claim";

    private final GuestDiaryClaimService guestDiaryClaimService;
    private final AuthenticatedUserIdResolver authenticatedUserIdResolver;
    private final GuestSessionCookieReader guestSessionCookieReader;
    private final GuestSessionCookieWriter guestSessionCookieWriter;
    private final ProblemDetailFactory problemDetailFactory;
    private final ZoneId serviceZoneId;

    GuestDiaryClaimController(
            GuestDiaryClaimService guestDiaryClaimService,
            AuthenticatedUserIdResolver authenticatedUserIdResolver,
            GuestSessionCookieReader guestSessionCookieReader,
            GuestSessionCookieWriter guestSessionCookieWriter,
            ProblemDetailFactory problemDetailFactory,
            ZoneId serviceZoneId
    ) {
        this.guestDiaryClaimService = guestDiaryClaimService;
        this.authenticatedUserIdResolver = authenticatedUserIdResolver;
        this.guestSessionCookieReader = guestSessionCookieReader;
        this.guestSessionCookieWriter = guestSessionCookieWriter;
        this.problemDetailFactory = problemDetailFactory;
        this.serviceZoneId = serviceZoneId;
    }

    @Operation(
            summary = "게스트 체험 일기 가져오기",
            description = "로그인 전에 작성한 게스트 체험 일기를 현재 회원에게 옮기고 게스트 세션 Cookie를 만료합니다. "
                    + "생성이 끝난 일기만 옮기며, 생성 중이거나 실패한 일기는 404를 반환합니다. "
                    + "같은 회원이 다시 요청하면 처음 결과를 반환합니다. 게스트 세션이 없거나 만료되었으면 "
                    + "401이 아닌 404를 반환합니다."
    )
    @ApiResponse(responseCode = "200", description = "체험 일기를 회원에게 옮김")
    @ApiErrorResponses({
            ErrorType.UNAUTHORIZED,
            ErrorType.INVALID_CURRENT_USER,
            ErrorType.FORBIDDEN,
            ErrorType.ACCOUNT_SUSPENDED,
            ErrorType.TERMS_ACCEPTANCE_REQUIRED,
            ErrorType.INVALID_CSRF_TOKEN,
            ErrorType.GUEST_DIARY_CLAIM_NOT_FOUND,
            ErrorType.GUEST_DIARY_ALREADY_CLAIMED,
            ErrorType.GUEST_DIARY_CLAIM_LIMIT_EXCEEDED
    })
    @PostMapping(PATH)
    public ResponseEntity<GuestDiaryClaimResponse> claim(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        UUID memberId = authenticatedUserIdResolver.resolve(authentication);
        String rawSessionToken = guestSessionCookieReader.read(request).orElse(null);
        GuestDiaryClaimResult result = guestDiaryClaimService.claim(memberId, rawSessionToken);
        guestSessionCookieWriter.expire(response);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(GuestDiaryClaimResponse.from(result, serviceZoneId));
    }

    @ExceptionHandler(InvalidCurrentUserException.class)
    ResponseEntity<ProblemDetail> handleInvalidCurrentUser(HttpServletRequest request) {
        return ResponseEntity.status(ErrorType.INVALID_CURRENT_USER.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .body(problemDetailFactory.create(ErrorType.INVALID_CURRENT_USER, request));
    }
}
