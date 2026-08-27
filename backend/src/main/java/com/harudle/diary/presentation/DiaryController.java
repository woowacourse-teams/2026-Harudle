package com.harudle.diary.presentation;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.diary.service.DiaryCreationService;
import com.harudle.diary.service.DiaryDeletionService;
import com.harudle.diary.service.DiaryQueryService;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Diary")
@SecurityRequirement(name = "bearerAuth")
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

    @Operation(
            summary = "일기 생성",
            description = "일기를 저장하고 4컷 이미지를 동기 생성합니다. 동일한 멱등성 요청의 성공 결과는 재사용합니다."
    )
    @PostMapping
    public ResponseEntity<CreateDiaryResponse> create(
            Authentication authentication,
            @Parameter(
                    description = "클라이언트가 생성한 UUID 형식의 멱등성 키",
                    required = true,
                    example = "7e5cc251-fdde-4cc0-a54e-2c8142750609"
            )
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

    @Operation(
            summary = "월간 일기 조회",
            description = "지정한 연·월의 모든 날짜와 일기 요약을 최신 날짜순으로 조회합니다."
    )
    @GetMapping
    public DiaryTimelineResponse getTimeline(
            Authentication authentication,
            @RequestParam @Min(MIN_API_YEAR) @Max(MAX_API_YEAR) int year,
            @RequestParam @Min(MIN_API_MONTH) @Max(MAX_API_MONTH) int month
    ) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        return responseAssembler.toTimelineResponse(diaryQueryService.getTimeline(userId, year, month));
    }

    @Operation(
            summary = "일기 상세 조회",
            description = "인증된 사용자가 소유한 삭제되지 않은 일기와 생성 결과를 조회합니다."
    )
    @GetMapping("/{diaryId}")
    public DiaryDetailResponse getDetail(
            Authentication authentication,
            @PathVariable UUID diaryId
    ) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        return responseAssembler.toDetailResponse(diaryQueryService.getDetail(userId, diaryId));
    }

    @Operation(
            summary = "일기 삭제",
            description = "본인 소유의 일기를 소프트 삭제합니다. 이미 없거나 삭제된 경우에도 성공합니다."
    )
    @DeleteMapping("/{diaryId}")
    public ResponseEntity<Void> delete(
            Authentication authentication,
            @PathVariable UUID diaryId
    ) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        diaryDeletionService.delete(userId, diaryId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "현재 연속 기록 조회",
            description = "성공한 생성 날짜를 기준으로 현재 연속 기록을 조회하며, 삭제된 일기의 날짜도 연속 기록에 유지합니다."
    )
    @GetMapping("/current-streak")
    public DiaryStreakResponse getCurrentStreak(
            Authentication authentication
    ) {
        UUID userId = authenticatedUserIdResolver.resolve(authentication);
        return responseAssembler.toStreakResponse(
                diaryQueryService.getCurrentStreak(userId)
        );
    }
}
