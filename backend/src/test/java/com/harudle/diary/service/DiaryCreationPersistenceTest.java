package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.TestcontainersConfiguration;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryResult;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.generation.diary.service.PendingDiaryGenerationClaimService;
import com.harudle.generation.loadtest.LoadTestDiaryGenerationExecutor;
import com.harudle.generation.loadtest.LoadTestGenerationRegistry;
import com.harudle.generation.usage.service.exception.DailyGenerationLimitExceededException;
import com.harudle.guest.domain.GuestSession;
import com.harudle.guest.infrastructure.token.GuestSessionTokenHasher;
import com.harudle.guest.repository.GuestSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import({TestcontainersConfiguration.class, DiaryCreationPersistenceTest.FixedClockConfiguration.class})
class DiaryCreationPersistenceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final String RAW_GUEST_TOKEN = "pending-guest-session-token";

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MemberDiaryCreationTransactionService transactionService;

    @Autowired
    private DiaryCreationClaimService claimService;

    @Autowired
    private GuestDiaryCreationTransactionService guestTransactionService;

    @Autowired
    private DiaryGenerationRepository generationRepository;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private GuestSessionTokenHasher tokenHasher;

    @Autowired
    private DiaryCreationService diaryCreationService;

    @Autowired
    private GuestDiaryCreationService guestDiaryCreationService;

    @Autowired
    private PendingDiaryGenerationClaimService pendingClaimService;

    @MockitoBean
    private DiaryGenerationExecutor generationExecutor;

    @MockitoBean(enforceOverride = true)
    private DiaryGenerationWorker generationWorker;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        when(generationExecutor.isConfigured()).thenReturn(true);
        executeUpdate(
                "INSERT INTO users (id, primary_email, name) VALUES (?, ?, ?)",
                USER_ID,
                "harudle@example.com",
                "하루들"
        );
        executeUpdate("""
                INSERT INTO generation_prompts (
                    storyboard_prompt_text,
                    image_style_prompt_text,
                    image_asset_object_key
                )
                VALUES (?, ?, ?)
                """, "스토리보드 프롬프트", "이미지 스타일 프롬프트", "references/style.png");
    }

    @AfterEach
    void tearDown() {
        executeUpdate("DELETE FROM users WHERE id = ?", USER_ID);
        executeUpdate(
                "DELETE FROM generation_prompts WHERE image_asset_object_key = ?",
                "references/style.png"
        );
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"PENDING", "PROCESSING"})
    @DisplayName("일기와 생성 기록 및 사용량을 하나의 트랜잭션으로 저장한다")
    void claimDiaryCreationAtomically(GenerationStatus initialStatus) {
        MemberDiaryCreationClaim memberClaim = createMemberClaim(initialStatus);
        DiaryCreationClaim claim = memberClaim.claim();

        assertThat(claim.newlyCreated()).isTrue();
        assertThat(claim.generationStatus()).isEqualTo(initialStatus);
        DiaryGeneration savedGeneration = generationRepository.findById(claim.generationId()).orElseThrow();
        assertThat(savedGeneration.getStatus()).isEqualTo(initialStatus);
        assertThat(savedGeneration.getUsageDate()).isEqualTo(DIARY_DATE);
        assertThat(memberClaim.usage().usedCount()).isEqualTo(1);
        assertThat(countRows("diaries")).isEqualTo(1);
        assertThat(countRows("diary_generations")).isEqualTo(1);
        assertThat(countRows("daily_generation_usage")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"PENDING", "PROCESSING"})
    @DisplayName("공통 생성 선점은 일기와 생성 기록만 저장하고 사용량을 변경하지 않는다")
    void claimDiaryCreationWithoutUsagePolicy(GenerationStatus initialStatus) {
        DiaryCreationClaim claim = transactionTemplate.execute(
                status -> initialStatus == GenerationStatus.PENDING
                        ? claimService.enqueue(createCommand(), true)
                        : claimService.claim(createCommand(), true)
        );

        assertThat(claim).isNotNull();
        assertThat(claim.newlyCreated()).isTrue();
        assertThat(claim.generationStatus()).isEqualTo(initialStatus);
        assertThat(generationRepository.findById(claim.generationId()).orElseThrow().getUsageDate()).isNull();
        assertThat(countRows("diaries")).isEqualTo(1);
        assertThat(countRows("diary_generations")).isEqualTo(1);
        assertThat(countRows("daily_generation_usage")).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"PENDING", "PROCESSING"})
    @DisplayName("생성 한도 초과 시 일기와 생성 기록 선점을 모두 롤백한다")
    void rollbackClaimWhenDailyLimitIsExceeded(GenerationStatus initialStatus) {
        executeUpdate("""
                INSERT INTO daily_generation_usage (
                    user_id,
                    usage_date,
                    used_count,
                    limit_count
                )
                VALUES (?, ?, 3, 3)
                """, USER_ID, DIARY_DATE);

        assertThatThrownBy(() -> createMemberClaim(initialStatus))
                .isInstanceOf(DailyGenerationLimitExceededException.class);
        assertThat(countRows("diaries")).isZero();
        assertThat(countRows("diary_generations")).isZero();
        assertThat(countRows("daily_generation_usage")).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 생성 횟수를 사용한 대기 요청도 다시 접수하면 작업과 사용량을 늘리지 않는다")
    void replayPendingCreationAtDailyLimit() {
        executeUpdate("""
                INSERT INTO daily_generation_usage (user_id, usage_date, used_count, limit_count)
                VALUES (?, ?, 2, 3)
                """, USER_ID, DIARY_DATE);

        MemberDiaryCreationClaim first = transactionService.enqueue(createCommand(), true);
        MemberDiaryCreationClaim replay = transactionService.enqueue(createCommand(), false);

        assertThat(first.claim().newlyCreated()).isTrue();
        assertThat(replay.claim().newlyCreated()).isFalse();
        assertThat(replay.claim().generationId()).isEqualTo(first.claim().generationId());
        assertThat(replay.claim().generationStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(replay.usage().usedCount()).isEqualTo(3);
        assertThat(countRows("diaries")).isEqualTo(1);
        assertThat(countRows("diary_generations")).isEqualTo(1);
    }

    @Test
    @DisplayName("게스트 대기 작업과 체험 사용을 함께 저장하고 같은 요청은 다시 사용하지 않는다")
    void enqueueGuestCreationAtomicallyAndReplay() {
        GuestSession session = saveGuestSession();
        CreateGuestDiaryCommand command = createGuestCommand();

        GuestDiaryCreationClaim first = guestTransactionService.enqueue(RAW_GUEST_TOKEN, command, true);
        Instant firstUsedAt = guestSessionRepository.findById(session.getId()).orElseThrow().getUsedAt();
        GuestDiaryCreationClaim replay = guestTransactionService.enqueue(RAW_GUEST_TOKEN, command, false);

        DiaryGeneration generation = generationRepository.findById(first.claim().generationId()).orElseThrow();
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(generation.getUsageDate()).isNull();
        assertThat(replay.claim().generationId()).isEqualTo(first.claim().generationId());
        assertThat(replay.claim().newlyCreated()).isFalse();
        GuestSession savedSession = guestSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(savedSession.isUsedForDiary(first.claim().diaryId())).isTrue();
        assertThat(firstUsedAt).isNotNull();
        assertThat(savedSession.getUsedAt()).isEqualTo(firstUsedAt);
        assertThat(countRows("diaries")).isEqualTo(1);
        assertThat(countRows("diary_generations")).isEqualTo(1);
        assertThat(countRows("daily_generation_usage")).isZero();
    }

    @Test
    @DisplayName("게스트 대기 접수를 롤백하면 일기와 작업 및 체험 사용이 모두 취소된다")
    void rollbackGuestPendingCreationAndTrialUsage() {
        GuestSession session = saveGuestSession();

        transactionTemplate.executeWithoutResult(status -> {
            guestTransactionService.enqueue(RAW_GUEST_TOKEN, createGuestCommand(), true);
            status.setRollbackOnly();
        });

        assertThat(countRows("diaries")).isZero();
        assertThat(countRows("diary_generations")).isZero();
        GuestSession savedSession = guestSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(savedSession.isUsed()).isFalse();
        assertThat(savedSession.getDiaryId()).isNull();
    }

    @Test
    @DisplayName("회원 생성 서비스는 저장한 대기 작업을 반환하고 작업 처리기가 나중에 선점한다")
    void memberServiceReturnsCommittedPendingGeneration() {
        CreateDiaryResult result = diaryCreationService.create(createCommand());

        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.generation().status()).isEqualTo(GenerationStatus.PENDING);
        assertThat(result.generation().imageObjectKey()).isNull();
        assertThat(result.usage().usedCount()).isEqualTo(1);
        assertThat(generationRepository.findById(result.generation().id()).orElseThrow().getStatus())
                .isEqualTo(GenerationStatus.PENDING);
        assertThat(pendingClaimService.claim(1)).containsExactly(result.generation().id());
        verify(generationExecutor, never()).generate(any(), any());
    }

    @Test
    @DisplayName("게스트 생성 서비스는 체험 사용과 대기 작업을 저장한 뒤 생성 없이 반환한다")
    void guestServiceReturnsCommittedPendingGeneration() {
        GuestSession session = saveGuestSession();

        CreateGuestDiaryResult result = guestDiaryCreationService.create(RAW_GUEST_TOKEN, createGuestCommand());

        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.generation().status()).isEqualTo(GenerationStatus.PENDING);
        assertThat(result.generation().imageObjectKey()).isNull();
        assertThat(guestSessionRepository.findById(session.getId()).orElseThrow().isUsedForDiary(result.id()))
                .isTrue();
        assertThat(countRows("daily_generation_usage")).isZero();
        assertThat(pendingClaimService.claim(1)).containsExactly(result.generation().id());
        verify(generationExecutor, never()).generate(any(), any());
    }

    @Test
    @DisplayName("테스트 대기 작업도 같은 선점 서비스를 사용하고 중복 접수는 사용량을 늘리지 않는다")
    void persistAndClaimLoadTestGeneration() {
        var stub = mock(LoadTestDiaryGenerationExecutor.class);
        var registry = new LoadTestGenerationRegistry(stub);
        MemberDiaryCreationClaim first = transactionService.enqueue(createCommand(), true, registry::register);
        MemberDiaryCreationClaim replay = transactionService.enqueue(createCommand(), false, registry::register);

        assertThat(replay.claim().generationId()).isEqualTo(first.claim().generationId());
        assertThat(replay.usage().usedCount()).isEqualTo(1);
        assertThat(registry.findExecutor(first.claim().generationId())).contains(stub);
        assertThat(pendingClaimService.claim(1)).containsExactly(first.claim().generationId());
    }

    @Test
    @DisplayName("테스트 작업 저장이 취소되면 일기와 사용량과 메모리 연결을 함께 지운다")
    void removeLoadTestRegistrationOnRollback() {
        var registry = new LoadTestGenerationRegistry(mock(LoadTestDiaryGenerationExecutor.class));
        UUID generationId = transactionTemplate.execute(status -> {
            MemberDiaryCreationClaim claim = transactionService.enqueue(createCommand(), true, registry::register);
            assertThat(registry.findExecutor(claim.claim().generationId())).isPresent();
            status.setRollbackOnly();
            return claim.claim().generationId();
        });

        assertThat(registry.findExecutor(generationId)).isEmpty();
        assertThat(countRows("diaries")).isZero();
        assertThat(countRows("diary_generations")).isZero();
        assertThat(countRows("daily_generation_usage")).isZero();
    }

    private MemberDiaryCreationClaim createMemberClaim(GenerationStatus initialStatus) {
        if (initialStatus == GenerationStatus.PENDING) {
            return transactionService.enqueue(createCommand(), true);
        }
        return transactionService.claim(createCommand(), true);
    }

    private GuestSession saveGuestSession() {
        Instant now = Instant.now();
        return guestSessionRepository.saveAndFlush(GuestSession.create(
                USER_ID, tokenHasher.hash(RAW_GUEST_TOKEN), now.plus(Duration.ofDays(30)), now.minusSeconds(1)
        ));
    }

    private CreateGuestDiaryCommand createGuestCommand() {
        return new CreateGuestDiaryCommand(DIARY_DATE, "오늘 친구와 카페에 갔다.", IDEMPOTENCY_KEY);
    }

    private CreateDiaryCommand createCommand() {
        return new CreateDiaryCommand(
                USER_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                IDEMPOTENCY_KEY
        );
    }

    private int countRows(String tableName) {
        String statement = "SELECT COUNT(*) FROM " + tableName;
        return transactionTemplate.execute(status -> {
            Number count = (Number) entityManager.createNativeQuery(statement).getSingleResult();
            return count.intValue();
        });
    }

    private void executeUpdate(String statement, Object... parameters) {
        transactionTemplate.executeWithoutResult(status -> {
            Query query = entityManager.createNativeQuery(statement);
            IntStream.range(0, parameters.length)
                    .forEach(index -> query.setParameter(index + 1, parameters[index]));
            query.executeUpdate();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        @Qualifier("serviceClock")
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
