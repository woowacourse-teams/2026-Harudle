package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.infrastructure.crypto.DiarySourceTextCryptoException;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.generation.config.StoryboardProviderProperties;
import com.harudle.generation.config.GenerationWorkerProperties;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.DiaryGenerationCompletionService;
import com.harudle.generation.diary.service.DiaryGenerationExecutor;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.generation.diary.service.PendingDiaryGenerationClaimService;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.StoryboardGenerator;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.GeneratedStoryboard;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import com.harudle.generation.diary.service.storyboard.StoryboardAttemptRunner;
import com.harudle.generation.diary.service.storyboard.StoryboardGenerationService;
import com.harudle.generation.prompt.domain.GenerationPrompt;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import com.harudle.share.service.ShareLinkQueryService;
import com.harudle.support.ServiceIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Import(DiarySourceTextEncryptionPersistenceTest.FixedClockConfiguration.class)
class DiarySourceTextEncryptionPersistenceTest extends ServiceIntegrationTestSupport {

    private static final UUID USER_ID = UUID.fromString("151a167f-b0d5-49cb-b5d3-96186711ec51");
    private static final UUID OTHER_USER_ID = UUID.fromString("4bdfb6c2-05b4-45b5-bbfe-096487a5c19b");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final String SOURCE_TEXT = "오늘 친구와 카페에 갔다.\n기분 좋은 하루였다. 😀";

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MemberDiaryCreationTransactionService transactionService;

    @Autowired
    private DiaryCreationClaimService claimService;

    @Autowired
    private DiaryCreationService creationService;

    @Autowired
    private DiaryQueryService queryService;

    @Autowired
    private ShareLinkQueryService shareQueryService;

    @Autowired
    private DiarySourceTextCipher sourceTextCipher;

    @Autowired
    private RequestFingerprintGenerator fingerprintGenerator;

    @Autowired
    private PendingDiaryGenerationClaimService pendingClaimService;

    @Autowired
    private DiaryGenerationRepository generationRepository;

    @Autowired
    private DiaryRepository diaryRepository;

    @Autowired
    private DiaryGenerationExecutor generationExecutor;

    @Autowired
    private DiaryGenerationCompletionService completionService;

    @Autowired
    private GenerationPromptRepository promptRepository;

    @MockitoBean
    private StoryboardGenerator storyboardGenerator;

    @MockitoBean
    private DiaryImageGenerator diaryImageGenerator;

    @MockitoBean
    private ImageStorage imageStorage;

    private GenerationPrompt prompt;

    @BeforeEach
    void setUp() {
        executeUpdate("INSERT INTO users (id, primary_email, name) VALUES (?, ?, ?)",
                USER_ID, "source-encryption@example.com", "하루들");
        executeUpdate("INSERT INTO users (id, primary_email, name) VALUES (?, ?, ?)",
                OTHER_USER_ID, "source-encryption-other@example.com", "다른 사용자");
        prompt = promptRepository.saveAndFlush(new GenerationPrompt(
                "스토리보드 프롬프트", "이미지 스타일 프롬프트", "references/encryption-test.png"
        ));
    }

    @AfterEach
    void tearDown() {
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", USER_ID, OTHER_USER_ID);
        if (prompt != null) {
            promptRepository.deleteById(prompt.getId());
        }
    }

    @Test
    @DisplayName("회원 생성은 원문을 v1 암호문으로 저장하고 상세와 멱등 재요청에는 원문을 반환한다")
    void persistEncryptedSourceAndReturnPlaintextOnlyWhenReading() {
        CreateDiaryCommand command = createCommand(SOURCE_TEXT);

        MemberDiaryCreationClaim created = transactionService.claim(command, true);
        StoredSource stored = findStoredSource(created.claim().diaryId());
        markGenerationSucceeded(created.claim());
        DiaryDetailResult detail = queryService.getDetail(USER_ID, created.claim().diaryId());
        MemberDiaryCreationClaim replayed = transactionService.claim(command, true);

        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.text()).isNotEqualTo(SOURCE_TEXT).doesNotContain(SOURCE_TEXT);
        String rawFingerprint = fingerprintGenerator.generate(new GenerateDiaryImageCommand(
                USER_ID, created.claim().diaryId(), DIARY_DATE, SOURCE_TEXT, command.idempotencyKey()
        ));
        assertThat(readValue("SELECT request_fingerprint FROM diary_generations WHERE id = ?",
                created.claim().generationId()))
                .isNotEqualTo(rawFingerprint)
                .isEqualTo(sourceTextCipher.protectRequestFingerprint(
                        created.claim().diaryId(), USER_ID, stored.text(), 1, rawFingerprint
                ));
        assertThat(created.claim().sourceText()).isEqualTo(SOURCE_TEXT);
        assertThat(detail.sourceText()).isEqualTo(SOURCE_TEXT);
        assertThat(replayed.claim().sourceText()).isEqualTo(SOURCE_TEXT);
        assertThat(replayed.claim().diaryId()).isEqualTo(created.claim().diaryId());
        assertThat(replayed.claim().newlyCreated()).isFalse();
        assertThat(replayed.usage().usedCount()).isEqualTo(1);
        assertThat(findStoredSource(created.claim().diaryId())).isEqualTo(stored);
        assertThat(countUserDiaries()).isEqualTo(1);
    }

    @Test
    @DisplayName("암호문과 HMAC으로 접수한 작업은 처리기가 원문으로 실행하고 완료 후 같은 요청으로 재조회한다")
    void completeRealCreationWithEncryptedSourceAndProtectedFingerprint() {
        CreateDiaryCommand command = createCommand(SOURCE_TEXT);
        GenerationTokenUsage tokenUsage = new GenerationTokenUsage(120, 350, 80, 550);
        Storyboard storyboard = new Storyboard(
                "친구와 보낸 하루", "같은 주인공이 모든 패널에 등장한다.",
                IntStream.rangeClosed(1, 4)
                        .mapToObj(index -> new StoryPanel(
                                index, index + "번째 장면", "카페", "친구와 나", "행복", List.of()
                        ))
                        .toList()
        );
        ReferenceImage referenceImage = new ReferenceImage(
                new ByteArrayResource(new byte[]{1}), MediaType.IMAGE_PNG
        );
        GeneratedImage generatedImage = new GeneratedImage(
                new ByteArrayResource(new byte[]{2}), MediaType.IMAGE_PNG
        );
        when(storyboardGenerator.generate(any(StoryboardGenerationRequest.class)))
                .thenReturn(new GeneratedStoryboard(storyboard, tokenUsage));
        when(imageStorage.load(prompt.getImageAssetObjectKey())).thenReturn(referenceImage);
        when(diaryImageGenerator.generate(any(DiaryImageGenerationRequest.class))).thenReturn(generatedImage);
        when(imageStorage.store(any(UUID.class), same(generatedImage)))
                .thenAnswer(invocation -> {
                    UUID generationId = invocation.getArgument(0);
                    return "generated/%s.png".formatted(generationId);
                });

        CreateDiaryResult created = creationService.create(command);
        StoredSource stored = findStoredSource(created.id());
        CreateDiaryResult pendingReplay = creationService.create(command);
        assertThat(created.generation().status()).isEqualTo(GenerationStatus.PENDING);
        assertThat(pendingReplay.generation()).isEqualTo(created.generation());
        assertThat(pendingReplay.newlyCreated()).isFalse();
        assertThat(pendingReplay.usage().usedCount()).isEqualTo(1);
        verify(storyboardGenerator, never()).generate(any(StoryboardGenerationRequest.class));

        DiaryGenerationWorker worker = new DiaryGenerationWorker(
                pendingClaimService, generationRepository, diaryRepository, sourceTextCipher,
                generationExecutor, Optional.empty(), completionService, Runnable::run,
                new GenerationWorkerProperties(1, Duration.ofSeconds(5))
        );
        worker.poll();

        CreateDiaryResult replayed = creationService.create(command);
        DiaryDetailResult detail = queryService.getDetail(USER_ID, created.id());

        String rawFingerprint = fingerprintGenerator.generate(new GenerateDiaryImageCommand(
                USER_ID, created.id(), DIARY_DATE, SOURCE_TEXT, command.idempotencyKey()
        ));
        assertThat(readValue("SELECT request_fingerprint FROM diary_generations WHERE id = ?",
                created.generation().id()))
                .isNotEqualTo(rawFingerprint)
                .isEqualTo(sourceTextCipher.protectRequestFingerprint(
                        created.id(), USER_ID, stored.text(), stored.version(), rawFingerprint
                ));
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.text()).isNotEqualTo(SOURCE_TEXT).doesNotContain(SOURCE_TEXT);
        assertThat(created.newlyCreated()).isTrue();
        assertThat(created.sourceText()).isEqualTo(SOURCE_TEXT);
        assertThat(created.generation().tokenUsage()).isNull();
        assertThat(detail.sourceText()).isEqualTo(SOURCE_TEXT);
        assertThat(detail.generation().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(detail.generation().title()).isEqualTo(storyboard.title());
        assertThat(detail.generation().imageObjectKey())
                .isEqualTo("generated/%s.png".formatted(created.generation().id()));
        assertThat(detail.generation().tokenUsage()).isEqualTo(tokenUsage);
        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(replayed.newlyCreated()).isFalse();
        assertThat(replayed.generation().id()).isEqualTo(created.generation().id());
        assertThat(replayed.generation().status()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(replayed.generation().tokenUsage()).isEqualTo(tokenUsage);
        assertThat(replayed.usage().usedCount()).isEqualTo(1);
        assertThat(findStoredSource(created.id())).isEqualTo(stored);
        assertThat(countUserDiaries()).isEqualTo(1);
        ArgumentCaptor<StoryboardGenerationRequest> request =
                ArgumentCaptor.forClass(StoryboardGenerationRequest.class);
        verify(storyboardGenerator).generate(request.capture());
        assertThat(request.getValue().diaryText()).isEqualTo(SOURCE_TEXT);
        assertThat(request.getValue().storyboardPromptText()).isEqualTo(prompt.getStoryboardPromptText());
        verify(diaryImageGenerator).generate(new DiaryImageGenerationRequest(
                storyboard, prompt.getImageStylePromptText(), referenceImage
        ));
        verify(imageStorage).store(eq(created.generation().id()), same(generatedImage));
        verify(imageStorage, never()).delete(any(String.class));
    }

    @Test
    @DisplayName("공통 생성 선점도 300 코드 포인트의 원문을 컬럼 길이 제한 없이 암호화한다")
    void persistMaximumLengthSourceThroughCommonClaim() {
        String maximumLengthSource = "하".repeat(299) + "😀";
        CreateDiaryCommand command = createCommand(maximumLengthSource);

        DiaryCreationClaim created = transactionTemplate.execute(status -> claimService.claim(command, true));

        assertThat(created).isNotNull();
        StoredSource stored = findStoredSource(created.diaryId());
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.text().length()).isGreaterThan(300);
        assertThat(created.sourceText()).isEqualTo(maximumLengthSource);
        assertThat(queryService.getDetail(USER_ID, created.diaryId()).sourceText())
                .isEqualTo(maximumLengthSource);
        assertThat(readValue("SELECT COUNT(*) FROM daily_generation_usage WHERE user_id = ?", USER_ID))
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("기존 v0 평문과 신규 v1 암호문이 섞여 있어도 상세와 재요청이 원문을 유지한다")
    void readAndReplayLegacyPlaintextAlongsideEncryptedDiary() {
        CreateDiaryCommand legacyCommand = createCommand("기존에 작성한 일기");
        UUID legacyDiaryId = insertLegacyDiary(legacyCommand);
        DiaryCreationClaim encrypted = transactionService.claim(createCommand(SOURCE_TEXT), true).claim();
        markGenerationSucceeded(encrypted);

        DiaryCreationClaim replayed = transactionService.claim(legacyCommand, true).claim();

        assertThat(queryService.getDetail(USER_ID, legacyDiaryId).sourceText())
                .isEqualTo(legacyCommand.sourceText());
        assertThat(queryService.getDetail(USER_ID, encrypted.diaryId()).sourceText()).isEqualTo(SOURCE_TEXT);
        assertThat(replayed.diaryId()).isEqualTo(legacyDiaryId);
        assertThat(replayed.sourceText()).isEqualTo(legacyCommand.sourceText());
        assertThat(replayed.newlyCreated()).isFalse();
        assertThat(findStoredSource(legacyDiaryId))
                .isEqualTo(new StoredSource(legacyCommand.sourceText(), 0));
        assertThat(findStoredSource(encrypted.diaryId()).version()).isEqualTo(1);
        assertThat(queryService.getTimeline(USER_ID, 2026, 8).days().stream()
                .flatMap(day -> day.items().stream()))
                .extracting(item -> item.id())
                .containsExactlyInAnyOrder(legacyDiaryId, encrypted.diaryId());
    }

    @Test
    @DisplayName("암호문이 변조된 타인 일기도 복호화 전에 소유권을 검사하고 본인 조회는 실패한다")
    void checkOwnershipBeforeDecryptingTamperedCiphertext() {
        DiaryCreationClaim created = transactionService.claim(createCommand(SOURCE_TEXT), true).claim();
        replaceWithCiphertextFromAnotherDiary(created.diaryId());

        assertThatThrownBy(() -> queryService.getDetail(OTHER_USER_ID, created.diaryId()))
                .isInstanceOf(DiaryAccessDeniedException.class);
        assertThatThrownBy(() -> queryService.getDetail(USER_ID, created.diaryId()))
                .isInstanceOf(DiarySourceTextCryptoException.class);
    }

    @Test
    @DisplayName("재요청 중 복호화 실패는 생성 중단과 사용량 복구 및 일기 삭제를 모두 롤백한다")
    void rollbackReplaySideEffectsWhenCiphertextCannotBeDecrypted() {
        CreateDiaryCommand command = createCommand(SOURCE_TEXT);
        DiaryCreationClaim created = transactionService.claim(command, true).claim();
        replaceWithCiphertextFromAnotherDiary(created.diaryId());
        StoredSource tampered = findStoredSource(created.diaryId());
        executeUpdate("UPDATE diary_generations SET updated_at = ? WHERE id = ?",
                NOW.minusSeconds(86_400), created.generationId());

        assertThatThrownBy(() -> transactionService.claim(command, true))
                .isInstanceOf(DiarySourceTextCryptoException.class);

        assertThat(readValue("SELECT status FROM diary_generations WHERE id = ?", created.generationId()))
                .isEqualTo("PROCESSING");
        assertThat(readValue("SELECT completed_at FROM diary_generations WHERE id = ?", created.generationId()))
                .isNull();
        assertThat(readValue("SELECT deleted_at FROM diaries WHERE id = ?", created.diaryId())).isNull();
        assertThat(readValue("SELECT used_count FROM daily_generation_usage WHERE user_id = ?", USER_ID))
                .isEqualTo(1);
        assertThat(findStoredSource(created.diaryId())).isEqualTo(tampered);
        assertThat(countUserDiaries()).isEqualTo(1);
    }

    @Test
    @DisplayName("월간 목록과 스트릭 및 공개 공유 조회는 손상된 원문을 복호화하지 않는다")
    void readSummariesAndPublicShareWithoutDecryptingSource() {
        DiaryCreationClaim created = transactionService.claim(createCommand(SOURCE_TEXT), true).claim();
        markGenerationSucceeded(created);
        replaceWithCiphertextFromAnotherDiary(created.diaryId());
        UUID shareId = UUID.randomUUID();
        executeUpdate("INSERT INTO share_links (id, generation_id) VALUES (?, ?)",
                shareId, created.generationId());

        assertThat(queryService.getTimeline(USER_ID, 2026, 8).days().stream()
                .flatMap(day -> day.items().stream()))
                .extracting(item -> item.id())
                .containsExactly(created.diaryId());
        assertThat(queryService.getCurrentStreak(USER_ID).streakCount()).isEqualTo(1);
        assertThat(shareQueryService.getPublicShare(shareId).title()).isEqualTo("오늘의 그림일기");
    }

    private CreateDiaryCommand createCommand(String sourceText) {
        return new CreateDiaryCommand(USER_ID, DIARY_DATE, sourceText, UUID.randomUUID());
    }

    private UUID insertLegacyDiary(CreateDiaryCommand command) {
        UUID diaryId = UUID.randomUUID();
        executeUpdate("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, ?, ?)",
                diaryId, command.userId(), command.diaryDate(), command.sourceText());
        String fingerprint = fingerprintGenerator.generate(new GenerateDiaryImageCommand(
                command.userId(), diaryId, command.diaryDate(), command.sourceText(), command.idempotencyKey()
        ));
        executeUpdate("""
                INSERT INTO diary_generations (
                    id, diary_id, prompt_id, idempotency_key, request_fingerprint,
                    status, title, image_object_key, completed_at
                ) VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?)
                """, UUID.randomUUID(), diaryId, prompt.getId(), command.idempotencyKey(), fingerprint,
                "기존 그림일기", "generated/%s.png".formatted(diaryId), NOW);
        return diaryId;
    }

    private void markGenerationSucceeded(DiaryCreationClaim claim) {
        executeUpdate("""
                UPDATE diary_generations
                SET status = 'SUCCEEDED', title = ?, image_object_key = ?, completed_at = ?
                WHERE id = ?
                """, "오늘의 그림일기", "generated/%s.png".formatted(claim.diaryId()), NOW, claim.generationId());
    }

    private void replaceWithCiphertextFromAnotherDiary(UUID diaryId) {
        String swappedCiphertext = sourceTextCipher.encrypt(UUID.randomUUID(), USER_ID, SOURCE_TEXT);
        executeUpdate("UPDATE diaries SET source_text = ? WHERE id = ?", swappedCiphertext, diaryId);
    }

    private StoredSource findStoredSource(UUID diaryId) {
        Object[] row = (Object[]) readValue(
                "SELECT source_text, source_text_version FROM diaries WHERE id = ?", diaryId
        );
        return new StoredSource((String) row[0], ((Number) row[1]).intValue());
    }

    private long countUserDiaries() {
        return ((Number) readValue("SELECT COUNT(*) FROM diaries WHERE user_id = ?", USER_ID)).longValue();
    }

    private Object readValue(String statement, Object... parameters) {
        return transactionTemplate.execute(status -> createQuery(statement, parameters).getSingleResult());
    }

    private void executeUpdate(String statement, Object... parameters) {
        transactionTemplate.executeWithoutResult(status -> createQuery(statement, parameters).executeUpdate());
    }

    private Query createQuery(String statement, Object... parameters) {
        Query query = entityManager.createNativeQuery(statement);
        IntStream.range(0, parameters.length)
                .forEach(index -> query.setParameter(index + 1, parameters[index]));
        return query;
    }

    private record StoredSource(String text, int version) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        @Qualifier("serviceClock")
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean(destroyMethod = "shutdownNow")
        ExecutorService encryptionTestStoryboardAttemptExecutor() {
            return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        }

        @Bean
        StoryboardGenerationService encryptionTestStoryboardService(
                StoryboardGenerator storyboardGenerator,
                @Qualifier("encryptionTestStoryboardAttemptExecutor") ExecutorService executor
        ) {
            StoryboardProviderProperties properties = new StoryboardProviderProperties(
                    "gemini", "none", Duration.ofSeconds(10), Duration.ofSeconds(10)
            );
            return new StoryboardGenerationService(
                    storyboardGenerator, Optional.empty(), new StoryboardAttemptRunner(executor), properties
            );
        }
    }
}
