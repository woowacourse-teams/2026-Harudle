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
@RequestMapping("/api/v1/diaries")
public class DiaryController {

    private final DiaryCreationService diaryCreationService;
    private final DiaryQueryService diaryQueryService;
    private final DiaryDeletionService diaryDeletionService;
    private final AuthenticatedUserIdResolver authenticatedUserIdResolver;
    private final DiaryResponseAssembler responseAssembler;

    public DiaryController(
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
                parseIdempotencyKey(idempotencyKey)
        ));
        CreateDiaryResponse response = responseAssembler.toCreateResponse(result);
        if (!result.newlyCreated()) {
            return ResponseEntity.ok(response);
        }
        URI location = URI.create("/api/v1/diaries/" + result.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public DiaryTimelineResponse getTimeline(
            Authentication authentication,
            @RequestParam @Min(1) @Max(9999) int year,
            @RequestParam @Min(1) @Max(12) int month
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

    private UUID parseIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidIdempotencyKeyException();
        }
        try {
            UUID parsedIdempotencyKey = UUID.fromString(idempotencyKey);
            if (!parsedIdempotencyKey.toString().equalsIgnoreCase(idempotencyKey)) {
                throw new InvalidIdempotencyKeyException();
            }
            return parsedIdempotencyKey;
        } catch (IllegalArgumentException exception) {
            throw new InvalidIdempotencyKeyException();
        }
    }
}
