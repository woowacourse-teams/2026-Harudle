package com.harudle.diary.presentation;

import com.harudle.diary.service.GuestDiaryCreationService;
import com.harudle.diary.service.GuestDiaryQueryService;
import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.guest.application.exception.GuestSessionRequiredException;
import com.harudle.guest.infrastructure.cookie.GuestSessionCookieReader;
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

    @PostMapping
    public ResponseEntity<GuestDiaryResponse> create(
            HttpServletRequest servletRequest,
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
