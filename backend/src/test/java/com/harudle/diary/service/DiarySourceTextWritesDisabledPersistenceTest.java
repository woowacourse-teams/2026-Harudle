package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import com.harudle.generation.loadtest.LoadTestDiaryGenerationExecutor;
import com.harudle.generation.loadtest.LoadTestGenerationRegistry;
import com.harudle.generation.prompt.domain.GenerationPrompt;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import com.harudle.guest.domain.GuestSession;
import com.harudle.guest.infrastructure.token.GuestSessionTokenHasher;
import com.harudle.guest.repository.GuestSessionRepository;
import com.harudle.support.ServiceIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "harudle.diary.source-text-encryption.new-writes-enabled=false")
class DiarySourceTextWritesDisabledPersistenceTest extends ServiceIntegrationTestSupport {

    private static final UUID USER_ID = UUID.fromString("655f5745-d285-4f54-a502-cc66b758d20a");
    private static final UUID GUEST_USER_ID = UUID.fromString("6fbe5a85-fd9f-4917-be2e-c3c56e521c7f");
    private static final String RAW_GUEST_TOKEN = "writes-disabled-guest-token";
    private static final String SOURCE_TEXT = "기존에 작성한 일기는 계속 읽는다.";
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 10, 20);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MemberDiaryCreationTransactionService memberTransactionService;

    @Autowired
    private GuestDiaryCreationTransactionService guestTransactionService;

    @Autowired
    private DiaryQueryService queryService;

    @Autowired
    private DiarySourceTextCipher sourceTextCipher;

    @Autowired
    private RequestFingerprintGenerator fingerprintGenerator;

    @Autowired
    private GenerationPromptRepository promptRepository;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private GuestSessionTokenHasher tokenHasher;

    @Autowired
    @Qualifier("authClock")
    private Clock authClock;

    private GenerationPrompt prompt;

    @BeforeEach
    void setUp() {
        executeUpdate("INSERT INTO users (id, primary_email, name) VALUES (?, ?, ?)",
                USER_ID, "writes-disabled@example.com", "하루들");
        executeUpdate("INSERT INTO users (id, primary_email, name) VALUES (?, ?, ?)",
                GUEST_USER_ID, "writes-disabled-guest@example.com", "게스트");
        prompt = promptRepository.saveAndFlush(new GenerationPrompt(
                "스토리보드 프롬프트", "이미지 스타일 프롬프트", "references/writes-disabled.png"
        ));
    }

    @AfterEach
    void tearDown() {
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", USER_ID, GUEST_USER_ID);
        if (prompt != null) {
            promptRepository.deleteById(prompt.getId());
        }
    }

    @Test
    @DisplayName("작성 차단 시 회원 일기와 생성 기록 및 사용량을 저장하지 않는다")
    void rejectMemberCreationWithoutConsumingUsage() {
        CreateDiaryCommand command = createCommand(USER_ID);

        assertThatThrownBy(() -> memberTransactionService.enqueue(command, true))
                .isInstanceOf(GenerationUnavailableException.class)
                .hasMessage("신규 일기 생성이 일시 중지되었습니다.");

        assertNoCreationSaved(USER_ID, command.idempotencyKey());
    }

    @Test
    @DisplayName("부하테스트 생성도 공통 선점의 작성 차단을 우회하지 못한다")
    void rejectLoadTestCreationBeforeCallingGenerationExecutor() {
        LoadTestDiaryGenerationExecutor executor = mock(LoadTestDiaryGenerationExecutor.class);
        when(executor.isConfigured()).thenReturn(true);
        LoadTestDiaryCreationService service = new LoadTestDiaryCreationService(
                memberTransactionService, new LoadTestGenerationRegistry(executor)
        );
        CreateDiaryCommand command = createCommand(USER_ID);

        assertThatThrownBy(() -> service.create(command)).isInstanceOf(GenerationUnavailableException.class);

        assertNoCreationSaved(USER_ID, command.idempotencyKey());
        verify(executor, never()).generate(any(), any());
    }

    @Test
    @DisplayName("작성 차단 시 게스트 체험 횟수와 연결할 일기를 소비하지 않는다")
    void rejectGuestCreationWithoutConsumingSession() {
        Instant now = authClock.instant();
        GuestSession session = guestSessionRepository.saveAndFlush(GuestSession.create(
                GUEST_USER_ID, tokenHasher.hash(RAW_GUEST_TOKEN), now.plusSeconds(3600), now
        ));
        CreateGuestDiaryCommand command = new CreateGuestDiaryCommand(DIARY_DATE, SOURCE_TEXT, UUID.randomUUID());

        assertThatThrownBy(() -> guestTransactionService.enqueue(RAW_GUEST_TOKEN, command, true))
                .isInstanceOf(GenerationUnavailableException.class);

        GuestSession unchanged = guestSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(unchanged.isUsed()).isFalse();
        assertThat(unchanged.getDiaryId()).isNull();
        assertNoCreationSaved(GUEST_USER_ID, command.idempotencyKey());
    }

    @ParameterizedTest
    @CsvSource({"0,PENDING", "1,PENDING", "0,SUCCEEDED", "1,SUCCEEDED"})
    @DisplayName("작성 차단 중에도 기존 평문과 암호문의 대기 및 성공 요청을 변경 없이 복원한다")
    void preserveDetailAndReplayForBothStorageVersions(int sourceTextVersion, GenerationStatus generationStatus) {
        CreateDiaryCommand command = createCommand(USER_ID);
        UUID diaryId = insertDiary(command, sourceTextVersion, generationStatus);
        Object[] before = (Object[]) readValue(
                "SELECT source_text, source_text_version FROM diaries WHERE id = ?", diaryId
        );

        Object storedFingerprint = readValue(
                "SELECT request_fingerprint FROM diary_generations WHERE diary_id = ?", diaryId
        );

        MemberDiaryCreationClaim replayed = memberTransactionService.enqueue(command, true);

        assertThat(queryService.getDetail(USER_ID, diaryId).sourceText()).isEqualTo(SOURCE_TEXT);
        assertThat(replayed.claim().diaryId()).isEqualTo(diaryId);
        assertThat(replayed.claim().sourceText()).isEqualTo(SOURCE_TEXT);
        assertThat(replayed.claim().generationStatus()).isEqualTo(generationStatus);
        assertThat(replayed.claim().newlyCreated()).isFalse();
        assertThat(replayed.usage().usedCount()).isZero();
        assertThat((Object[]) readValue(
                "SELECT source_text, source_text_version FROM diaries WHERE id = ?", diaryId
        )).containsExactly(before);
        assertThat(readValue("SELECT request_fingerprint FROM diary_generations WHERE diary_id = ?", diaryId))
                .isEqualTo(storedFingerprint);
        assertThat(readValue("SELECT COUNT(*) FROM diaries WHERE user_id = ?", USER_ID)).isEqualTo(1L);
    }

    private CreateDiaryCommand createCommand(UUID userId) {
        return new CreateDiaryCommand(userId, DIARY_DATE, SOURCE_TEXT, UUID.randomUUID());
    }

    private UUID insertDiary(CreateDiaryCommand command, int sourceTextVersion, GenerationStatus generationStatus) {
        UUID diaryId = UUID.randomUUID();
        String storedText = sourceTextVersion == 0 ? SOURCE_TEXT
                : sourceTextCipher.encrypt(diaryId, command.userId(), SOURCE_TEXT);
        executeUpdate("""
                INSERT INTO diaries (id, user_id, diary_date, source_text, source_text_version)
                VALUES (?, ?, ?, ?, ?)
                """, diaryId, command.userId(), DIARY_DATE, storedText, sourceTextVersion);
        String rawFingerprint = fingerprintGenerator.generate(new GenerateDiaryImageCommand(
                command.userId(), diaryId, DIARY_DATE, SOURCE_TEXT, command.idempotencyKey()
        ));
        String storedFingerprint = sourceTextCipher.protectRequestFingerprint(
                diaryId, command.userId(), storedText, sourceTextVersion, rawFingerprint
        );
        executeUpdate("""
                INSERT INTO diary_generations (
                    id, diary_id, prompt_id, idempotency_key, request_fingerprint,
                    status, title, image_object_key, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), diaryId, prompt.getId(), command.idempotencyKey(), storedFingerprint,
                generationStatus.name(),
                generationStatus == GenerationStatus.SUCCEEDED ? "기존 그림일기" : null,
                generationStatus == GenerationStatus.SUCCEEDED ? "generated/%s.png".formatted(diaryId) : null,
                generationStatus == GenerationStatus.SUCCEEDED ? authClock.instant() : null);
        return diaryId;
    }

    private void assertNoCreationSaved(UUID userId, UUID idempotencyKey) {
        assertThat(readValue("SELECT COUNT(*) FROM diaries WHERE user_id = ?", userId)).isEqualTo(0L);
        assertThat(readValue("SELECT COUNT(*) FROM diary_generations WHERE idempotency_key = ?", idempotencyKey))
                .isEqualTo(0L);
        assertThat(readValue("SELECT COUNT(*) FROM daily_generation_usage WHERE user_id = ?", userId))
                .isEqualTo(0L);
    }

    private Object readValue(String statement, Object... parameters) {
        return transactionTemplate.execute(status -> createQuery(statement, parameters).getSingleResult());
    }

    private void executeUpdate(String statement, Object... parameters) {
        transactionTemplate.executeWithoutResult(status -> createQuery(statement, parameters).executeUpdate());
    }

    private Query createQuery(String statement, Object... parameters) {
        Query query = entityManager.createNativeQuery(statement);
        IntStream.range(0, parameters.length).forEach(index -> query.setParameter(index + 1, parameters[index]));
        return query;
    }
}
