package com.harudle.diary.presentation;

import com.harudle.auth.domain.UserRole;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.validation.IdempotencyKeyParser;
import com.harudle.diary.service.LoadTestDiaryCreationService;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.generation.loadtest.LoadTestAiStub;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@ConditionalOnProperty(name = "DEPLOY_ENV", havingValue = "dev")
@ConditionalOnProperty(name = "harudle.generation.adapters.enabled", havingValue = "true")
@RequestMapping("/api/v1/load-test")
class LoadTestDiaryController {

    private final LoadTestDiaryCreationService diaryCreationService;
    private final AuthenticatedUserIdResolver authenticatedUserIdResolver;
    private final DiaryResponseAssembler responseAssembler;
    private final UserRepository users;

    LoadTestDiaryController(
            LoadTestDiaryCreationService diaryCreationService,
            AuthenticatedUserIdResolver authenticatedUserIdResolver,
            DiaryResponseAssembler responseAssembler,
            UserRepository users
    ) {
        this.diaryCreationService = diaryCreationService;
        this.authenticatedUserIdResolver = authenticatedUserIdResolver;
        this.responseAssembler = responseAssembler;
        this.users = users;
    }

    @GetMapping("/ai-stub")
    ResponseEntity<Status> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Status("dev", LoadTestAiStub.DELAY.toMillis(), "test-image", "real",
                        "db-queue", diaryCreationService.isWorkerEnabled()));
    }

    @PostMapping("/diaries")
    ResponseEntity<CreateDiaryResponse> create(
            Authentication authentication,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateDiaryRequest request
    ) {
        var userId = authenticatedUserIdResolver.resolve(authentication);
        boolean testMember = users.findById(userId).filter(user -> !user.isDeleted()
                && user.getRole() == UserRole.USER && user.getPrimaryEmail() != null
                && user.getPrimaryEmail().matches("[0-9]{1,3}@[0-9a-f]{32}\\.loadtest\\.invalid")).isPresent();
        if (!testMember) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var result = diaryCreationService.create(new CreateDiaryCommand(
                userId, request.diaryDate(), request.sourceText(),
                IdempotencyKeyParser.parse(idempotencyKey)
        ));
        var response = responseAssembler.toCreateResponse(result);
        if (!result.newlyCreated()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.accepted()
                .location(URI.create(DiaryController.BASE_PATH + "/" + result.id()))
                .body(response);
    }

    record Status(String environment, long delayMs, String output, String storage, String execution, boolean workerEnabled) {}
}
