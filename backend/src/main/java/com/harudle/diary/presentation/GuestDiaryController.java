package com.harudle.diary.presentation;

import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ErrorType;
import com.harudle.common.validation.IdempotencyKeyParser;
import com.harudle.diary.service.GuestDiaryCreationService;
import com.harudle.diary.service.GuestDiaryQueryService;
import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.guest.application.exception.GuestSessionRequiredException;
import com.harudle.guest.infrastructure.cookie.GuestSessionCookieReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Guest")
@RestController
@RequestMapping(GuestDiaryController.BASE_PATH)
class GuestDiaryController {

    static final String BASE_PATH = "/api/v1/guest/diaries";

    private final GuestDiaryCreationService guestDiaryCreationService;
    private final GuestDiaryQueryService guestDiaryQueryService;
    private final GuestSessionCookieReader guestSessionCookieReader;
    private final DiaryResponseAssembler responseAssembler;

    GuestDiaryController(
            GuestDiaryCreationService guestDiaryCreationService,
            GuestDiaryQueryService guestDiaryQueryService,
            GuestSessionCookieReader guestSessionCookieReader,
            DiaryResponseAssembler responseAssembler
    ) {
        this.guestDiaryCreationService = guestDiaryCreationService;
        this.guestDiaryQueryService = guestDiaryQueryService;
        this.guestSessionCookieReader = guestSessionCookieReader;
        this.responseAssembler = responseAssembler;
    }

    @Operation(
            summary = "게스트 일기 생성",
            description = "게스트 세션의 1회 체험 권한으로 일기와 4컷 이미지를 생성합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "새 게스트 일기 생성 완료"),
            @ApiResponse(responseCode = "200", description = "멱등 재요청의 기존 게스트 일기 반환")
    })
    @ApiErrorResponses({
            ErrorType.VALIDATION_ERROR,
            ErrorType.INVALID_IDEMPOTENCY_KEY,
            ErrorType.GUEST_SESSION_REQUIRED,
            ErrorType.GUEST_SESSION_EXPIRED,
            ErrorType.INVALID_CSRF_TOKEN,
            ErrorType.DIARY_NOT_FOUND,
            ErrorType.GUEST_TRIAL_ALREADY_USED,
            ErrorType.IDEMPOTENCY_KEY_CONFLICT,
            ErrorType.GENERATION_IN_PROGRESS,
            ErrorType.AI_PROVIDER_ERROR,
            ErrorType.GENERATION_UNAVAILABLE,
            ErrorType.GENERATION_INTERRUPTED,
            ErrorType.IMAGE_STORAGE_ERROR,
            ErrorType.AI_PROVIDER_TIMEOUT
    })
    @PostMapping
    public ResponseEntity<GuestDiaryResponse> create(
            HttpServletRequest servletRequest,
            @Parameter(
                    description = "클라이언트가 생성한 UUID 형식의 멱등성 키",
                    required = true,
                    example = "7e5cc251-fdde-4cc0-a54e-2c8142750609"
            )
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateDiaryRequest request
    ) {
        String rawSessionToken = requireGuestSessionToken(servletRequest);
        CreateGuestDiaryResult result = guestDiaryCreationService.create(
                rawSessionToken,
                new CreateGuestDiaryCommand(
                        request.diaryDate(),
                        request.sourceText(),
                        IdempotencyKeyParser.parse(idempotencyKey)
                )
        );
        GuestDiaryResponse response = responseAssembler.toGuestCreateResponse(result);

        if (!result.newlyCreated()) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(response);
        }

        URI location = URI.create(BASE_PATH + "/" + result.id());
        return ResponseEntity.created(location)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @Operation(
            summary = "게스트 일기 결과 조회",
            description = "현재 게스트 세션에 연결된 일기 생성 결과를 조회합니다."
    )
    @ApiErrorResponses({
            ErrorType.VALIDATION_ERROR,
            ErrorType.GUEST_SESSION_REQUIRED,
            ErrorType.GUEST_SESSION_EXPIRED,
            ErrorType.DIARY_NOT_FOUND,
            ErrorType.IMAGE_STORAGE_ERROR
    })
    @GetMapping("/{diaryId}")
    public ResponseEntity<GuestDiaryResponse> getDetail(
            HttpServletRequest servletRequest,
            @PathVariable UUID diaryId
    ) {
        String rawSessionToken = requireGuestSessionToken(servletRequest);
        DiaryDetailResult result = guestDiaryQueryService.getDetail(rawSessionToken, diaryId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(responseAssembler.toGuestDetailResponse(result));
    }

    private String requireGuestSessionToken(HttpServletRequest request) {
        return guestSessionCookieReader.read(request)
                .orElseThrow(GuestSessionRequiredException::new);
    }
}
