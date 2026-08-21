package com.harudle.diary.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.diary.service.DiaryCreationService;
import com.harudle.diary.service.DiaryDeletionService;
import com.harudle.diary.service.DiaryQueryService;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping(DiaryController.BASE_PATH)
class DiaryController {

    static final String BASE_PATH = "/api/v1/diaries";
    private static final int MIN_API_YEAR = 1;
    private static final int MAX_API_YEAR = 9999;
    private static final int MIN_API_MONTH = 1;
    private static final int MAX_API_MONTH = 12;

    private final DiaryCreationService diaryCreationService;
    private final DiaryQueryService diaryQueryService;
    private final DiaryDeletionService diaryDeletionService;
    private final AuthenticatedUserIdResolver authenticatedUserIdResolver;
    private final DiaryResponseAssembler responseAssembler;

    DiaryController(
            DiaryCreationService diaryCreationService,
            DiaryQueryService diaryQueryService,
            DiaryDeletionService diaryDeletionService,
            AuthenticatedUserIdResolver authenticatedUserIdResolver,
            DiaryResponseAssembler responseAssembler
    ) {
        this.diaryCreationService = diaryCreationService;
        this.diaryQueryService = diaryQueryService;
        this.diaryDeletionService = diaryDeletionService;
        this.authenticatedUserIdResolver = authenticatedUserIdResolver;
        this.responseAssembler = responseAssembler;
    }

    @PostMapping
    public ResponseEntity<CreateDiaryResponse> create(
            Authentication authentication,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateDiaryRequest request
    ) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        CreateDiaryResult result = diaryCreationService.create(new CreateDiaryCommand(
                userId,
                request.diaryDate(),
                request.sourceText(),
                IdempotencyKeyParser.parse(idempotencyKey)
        ));
        CreateDiaryResponse response = responseAssembler.toCreateResponse(result);
        if (!result.newlyCreated()) {
            return ResponseEntity.ok(response);
        }
        URI location = URI.create(BASE_PATH + "/" + result.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public DiaryTimelineResponse getTimeline(
            Authentication authentication,
            @RequestParam @Min(MIN_API_YEAR) @Max(MAX_API_YEAR) int year,
            @RequestParam @Min(MIN_API_MONTH) @Max(MAX_API_MONTH) int month
    ) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        return responseAssembler.toTimelineResponse(diaryQueryService.getTimeline(userId, year, month));
    }

    @GetMapping("/{diaryId}")
    public DiaryDetailResponse getDetail(
            Authentication authentication,
            @PathVariable UUID diaryId
    ) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        return responseAssembler.toDetailResponse(diaryQueryService.getDetail(userId, diaryId));
    }

    @DeleteMapping("/{diaryId}")
    public ResponseEntity<Void> delete(
            Authentication authentication,
            @PathVariable UUID diaryId
    ) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        diaryDeletionService.delete(userId, diaryId);
        return ResponseEntity.noContent().build();
    }

}
