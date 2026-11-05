package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.WithdrawalDatabaseService;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.GuestDiaryClaimResult;
import com.harudle.generation.config.GenerationWorkerProperties;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.DiaryGenerationCompletionService;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.generation.diary.service.PendingDiaryGenerationClaimService;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.guest.application.exception.GuestDiaryAlreadyClaimedException;
import com.harudle.guest.application.exception.GuestDiaryClaimLimitExceededException;
import com.harudle.guest.domain.GuestSession;
import com.harudle.guest.infrastructure.token.GuestSessionTokenHasher;
import com.harudle.guest.repository.GuestSessionRepository;
import com.harudle.support.ServiceIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class GuestDiaryClaimPersistenceTest extends ServiceIntegrationTestSupport {

    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 11, 5);
    private static final String SUCCESS = "SUCCESS";

    @Autowired
    private GuestDiaryClaimService claimService;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private GuestSessionTokenHasher tokenHasher;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DiaryRepository diaryRepository;

    @Autowired
    private DiaryGenerationRepository diaryGenerationRepository;

    @Autowired
    private DiaryGenerationCompletionService completionService;

    @Autowired
    private RequestFingerprintGenerator fingerprintGenerator;

    @Autowired
    private WithdrawalDatabaseService withdrawalDatabaseService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private Instant now;
    private long promptId;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        promptId = jdbcTemplate.queryForObject("""
                INSERT INTO generation_prompts (
                    storyboard_prompt_text, image_style_prompt_text, image_asset_object_key
                )
                VALUES ('스토리보드 프롬프트', '이미지 스타일 프롬프트', 'references/claim-persistence.png')
                RETURNING id
                """, Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM withdrawal_image_cleanup_tasks WHERE object_key LIKE 'generated/claim-%'");
        createdUserIds.forEach(userId -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId));
        jdbcTemplate.update("DELETE FROM generation_prompts WHERE id = ?", promptId);
    }

    @Test
    @DisplayName("한 회원이 서로 다른 게스트 세션으로 동시에 가져와도 하나만 성공한다")
    void allowOnlyOneConcurrentClaimPerMember() throws Exception {
        User member = saveUser("회원");
        GuestFixture first = saveGuestWithDiary("first-token", GenerationStatus.SUCCEEDED);
        GuestFixture second = saveGuestWithDiary("second-token", GenerationStatus.SUCCEEDED);

        List<String> outcomes = runConcurrently(
                () -> claimService.claim(member.getId(), "first-token"),
                () -> claimService.claim(member.getId(), "second-token")
        );

        assertThat(outcomes).containsExactlyInAnyOrder(
                SUCCESS,
                GuestDiaryClaimLimitExceededException.class.getSimpleName()
        );
        long ownedByMember = List.of(first, second).stream()
                .filter(guest -> member.getId().equals(findDiaryOwner(guest.diaryId())))
                .count();
        assertThat(ownedByMember).isEqualTo(1);
    }

    @Test
    @DisplayName("두 회원이 같은 게스트 세션을 동시에 가져와도 한 회원만 가져간다")
    void allowOnlyOneMemberForConcurrentClaimsOfSameSession() throws Exception {
        User member = saveUser("회원");
        User anotherMember = saveUser("다른 회원");
        GuestFixture guest = saveGuestWithDiary("guest-token", GenerationStatus.SUCCEEDED);

        List<String> outcomes = runConcurrently(
                () -> claimService.claim(member.getId(), "guest-token"),
                () -> claimService.claim(anotherMember.getId(), "guest-token")
        );

        assertThat(outcomes).containsExactlyInAnyOrder(
                SUCCESS,
                GuestDiaryAlreadyClaimedException.class.getSimpleName()
        );
        GuestSession session = findSession("guest-token");
        assertThat(findDiaryOwner(guest.diaryId())).isEqualTo(session.getClaimedByUserId());
    }

    @Test
    @DisplayName("대기 중에 가져간 일기도 작업 처리기가 원래 요청과 같은 생성 요청으로 실행한다")
    void executePendingGenerationAfterClaim() {
        User member = saveUser("회원");
        GuestFixture guest = saveGuestWithDiary("guest-token", GenerationStatus.PENDING);
        GuestDiaryClaimResult claim = claimService.claim(member.getId(), "guest-token");
        AtomicReference<Boolean> claimMatched = new AtomicReference<>();

        workerFor(guest.generationId(), () -> { }, claimMatched).poll();

        assertThat(claim.generationStatus()).isEqualTo(GenerationStatus.PENDING);
        assertExecutedForMember(guest, member, claimMatched);
    }

    @Test
    @DisplayName("처리기가 선점한 직후 가져가도 원래 요청과 같은 생성 요청으로 실행한다")
    void executeProcessingGenerationClaimedBeforeInputLoad() {
        User member = saveUser("회원");
        GuestFixture guest = saveGuestWithDiary("guest-token", GenerationStatus.PENDING);
        AtomicReference<GuestDiaryClaimResult> claim = new AtomicReference<>();
        AtomicReference<Boolean> claimMatched = new AtomicReference<>();

        workerFor(
                guest.generationId(),
                () -> claim.set(claimService.claim(member.getId(), "guest-token")),
                claimMatched
        ).poll();

        assertThat(claim.get().generationStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertExecutedForMember(guest, member, claimMatched);
    }

    @Test
    @DisplayName("체험 일기를 가져간 회원이 탈퇴해도 제약 위반 없이 가져간 기록을 남긴다")
    void withdrawMemberAfterClaim() {
        User member = saveUser("회원");
        User anotherMember = saveUser("다른 회원");
        GuestFixture guest = saveGuestWithDiary("guest-token", GenerationStatus.SUCCEEDED);
        claimService.claim(member.getId(), "guest-token");

        withdrawalDatabaseService.delete(member.getId());
        createdUserIds.remove(member.getId());

        GuestSession session = findSession("guest-token");
        assertThat(session.getClaimedByUserId()).isNull();
        assertThat(session.getClaimedAt()).isNotNull();
        assertThat(session.getDiaryId()).isNull();
        assertThat(diaryRepository.findById(guest.diaryId())).isEmpty();
        assertThat(runCatching(() -> claimService.claim(anotherMember.getId(), "guest-token")))
                .isEqualTo(GuestDiaryAlreadyClaimedException.class.getSimpleName());
    }

    private DiaryGenerationWorker workerFor(
            UUID generationId,
            Runnable afterProcessingStarted,
            AtomicReference<Boolean> claimMatched
    ) {
        PendingDiaryGenerationClaimService pendingClaimService = mock(PendingDiaryGenerationClaimService.class);
        when(pendingClaimService.claim(1)).thenAnswer(invocation -> {
            transactionTemplate.executeWithoutResult(status -> diaryGenerationRepository
                    .findByIdForUpdate(generationId)
                    .orElseThrow()
                    .startProcessing());
            afterProcessingStarted.run();
            return List.of(generationId);
        });
        DiaryGenerationExecutor executor = mock(DiaryGenerationExecutor.class);
        when(executor.isConfigured()).thenReturn(true);
        when(executor.generate(any(), eq(generationId))).thenAnswer(invocation -> {
            GenerateDiaryImageCommand command = invocation.getArgument(0);
            DiaryGeneration stored = diaryGenerationRepository.findById(generationId).orElseThrow();
            claimMatched.set(stored.matchesExecutableClaim(
                    command.diaryId(),
                    command.idempotencyKey(),
                    fingerprintGenerator.generate(command)
            ));
            return null;
        });
        return new DiaryGenerationWorker(
                pendingClaimService,
                diaryGenerationRepository,
                diaryRepository,
                executor,
                Optional.empty(),
                completionService,
                Runnable::run,
                new GenerationWorkerProperties(1, Duration.ofSeconds(5))
        );
    }

    private void assertExecutedForMember(
            GuestFixture guest,
            User member,
            AtomicReference<Boolean> claimMatched
    ) {
        assertThat(claimMatched.get()).isTrue();
        DiaryGeneration generation = diaryGenerationRepository.findById(guest.generationId()).orElseThrow();
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(generation.getRequestedByUserId()).isEqualTo(guest.guestUserId());
        Diary diary = diaryRepository.findById(guest.diaryId()).orElseThrow();
        assertThat(diary.isDeleted()).isFalse();
        assertThat(diary.getUserId()).isEqualTo(member.getId());
    }

    private List<String> runConcurrently(Callable<?> first, Callable<?> second) throws Exception {
        List<Callable<?>> tasks = List.of(first, second);
        ExecutorService executorService = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = tasks.stream()
                    .map(task -> executorService.submit(() -> {
                        ready.countDown();
                        start.await();
                        return runCatching(task);
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executorService.shutdownNow();
            assertThat(executorService.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String runCatching(Callable<?> task) {
        try {
            task.call();
            return SUCCESS;
        } catch (Exception exception) {
            return exception.getClass().getSimpleName();
        }
    }

    private GuestFixture saveGuestWithDiary(String rawToken, GenerationStatus status) {
        User guestUser = saveUser("게스트");
        Diary diary = diaryRepository.saveAndFlush(Diary.create(guestUser.getId(), DIARY_DATE, "게스트 체험 일기"));
        UUID idempotencyKey = UUID.randomUUID();
        String fingerprint = fingerprintGenerator.generate(new GenerateDiaryImageCommand(
                guestUser.getId(), diary.getId(), diary.getDiaryDate(), diary.getSourceText(), idempotencyKey
        ));
        DiaryGeneration generation = DiaryGeneration.pending(
                diary.getId(), guestUser.getId(), promptId, idempotencyKey, fingerprint, null
        );
        if (status == GenerationStatus.SUCCEEDED) {
            generation.startProcessing();
            String imageKey = "generated/claim-" + generation.getId() + "/image.png";
            generation.succeed(TestStoryboardFactory.create(), imageKey, now);
        }
        diaryGenerationRepository.saveAndFlush(generation);
        GuestSession session = GuestSession.create(
                guestUser.getId(),
                tokenHasher.hash(rawToken),
                now.plus(Duration.ofDays(30)),
                now.minus(Duration.ofHours(1))
        );
        session.useForDiary(diary.getId(), now.minus(Duration.ofMinutes(30)));
        guestSessionRepository.saveAndFlush(session);
        return new GuestFixture(guestUser.getId(), diary.getId(), generation.getId());
    }

    private User saveUser(String name) {
        User user = userRepository.saveAndFlush(new User(null, name, now));
        createdUserIds.add(user.getId());
        return user;
    }

    private GuestSession findSession(String rawToken) {
        return guestSessionRepository.findByTokenHash(tokenHasher.hash(rawToken)).orElseThrow();
    }

    private UUID findDiaryOwner(UUID diaryId) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM diaries WHERE id = ?", UUID.class, diaryId);
    }

    private record GuestFixture(UUID guestUserId, UUID diaryId, UUID generationId) {
    }
}
