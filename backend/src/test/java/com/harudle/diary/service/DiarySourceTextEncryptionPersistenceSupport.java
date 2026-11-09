package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.generation.config.GenerationLifecycleProperties;
import com.harudle.generation.config.GenerationWorkerProperties;
import com.harudle.generation.config.StoryboardProviderProperties;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.repository.DiaryGenerationAdmissionLock;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.ClaimedDiaryGenerationService;
import com.harudle.generation.diary.service.DiaryGenerationCompletionService;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.generation.diary.service.GenerationLifecycleMetrics;
import com.harudle.generation.diary.service.GenerationQueueMetrics;
import com.harudle.generation.diary.service.PendingDiaryGenerationClaimService;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.RequestFingerprintProtector;
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
import com.harudle.generation.usage.service.GenerationUsageService;
import com.harudle.share.service.ShareLinkQueryService;
import com.harudle.support.ServiceIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared fixtures keep application and maintenance persistence checks on the same Spring configuration. */

abstract class DiarySourceTextEncryptionPersistenceSupport extends ServiceIntegrationTestSupport {

    protected static final UUID USER_ID = UUID.fromString("151a167f-b0d5-49cb-b5d3-96186711ec51");
    protected static final UUID OTHER_USER_ID = UUID.fromString("4bdfb6c2-05b4-45b5-bbfe-096487a5c19b");
    protected static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    protected static final String SOURCE_TEXT = "오늘 친구와 카페에 갔다.\n기분 좋은 하루였다. 😀";

    @PersistenceContext
    protected EntityManager entityManager;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    @Autowired
    protected MemberDiaryCreationTransactionService transactionService;

    @Autowired
    protected DiaryCreationClaimService claimService;

    @Autowired
    protected DiaryQueryService queryService;

    @Autowired
    protected ShareLinkQueryService shareQueryService;

    @Autowired
    protected DiarySourceTextCipher sourceTextCipher;

    @Autowired
    protected RequestFingerprintGenerator fingerprintGenerator;

    @Autowired
    protected PendingDiaryGenerationClaimService pendingClaimService;

    @Autowired
    protected DiaryGenerationRepository generationRepository;

    @Autowired
    protected DiaryRepository diaryRepository;

    @Autowired
    protected RequestFingerprintProtector fingerprintProtector;

    @Autowired
    protected GenerationLifecycleMetrics lifecycleMetrics;

    @Autowired
    @Qualifier("serviceClock")
    protected Clock serviceClock;

    @Autowired
    protected DiaryGenerationCompletionService completionService;

    @Autowired
    protected GenerationPromptRepository promptRepository;

    @Autowired
    protected GenerationLifecycleProperties lifecycleProperties;

    @Autowired
    protected GenerationWorkerProperties workerProperties;

    @Autowired
    protected DiaryGenerationAdmissionLock admissionLock;

    @Autowired
    protected GenerationQueueMetrics queueMetrics;

    @Autowired
    protected GenerationUsageService usageService;

    protected GenerationPrompt prompt;

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

    protected void assertRealCreationWithEncryptedSourceAndProtectedFingerprint(PendingPreparation preparation)
            throws SQLException {
        StoryboardGenerator storyboardGenerator = mock(StoryboardGenerator.class);
        DiaryImageGenerator diaryImageGenerator = mock(DiaryImageGenerator.class);
        ImageStorage imageStorage = mock(ImageStorage.class);
        try (ExecutorService attempts = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            StoryboardGenerationService storyboardService = new StoryboardGenerationService(
                    storyboardGenerator, Optional.empty(), new StoryboardAttemptRunner(attempts),
                    new StoryboardProviderProperties("gemini", "none", Duration.ofSeconds(10), Duration.ofSeconds(10))
            );
            var generationExecutor = new ClaimedDiaryGenerationService(
                    fingerprintGenerator, fingerprintProtector, promptRepository, generationRepository,
                    storyboardService, diaryImageGenerator, imageStorage, completionService, lifecycleMetrics
            );
            DiaryCreationService creationService = new DiaryCreationService(transactionService, generationExecutor);
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
            CreateDiaryResult pendingReplay = creationService.create(command);
            assertThat(created.generation().status()).isEqualTo(GenerationStatus.PENDING);
            assertThat(pendingReplay.generation()).isEqualTo(created.generation());
            assertThat(pendingReplay.newlyCreated()).isFalse();
            assertThat(pendingReplay.usage().usedCount()).isEqualTo(1);
            verify(storyboardGenerator, never()).generate(any(StoryboardGenerationRequest.class));

            preparation.prepare(command, created);
            StoredSource stored = findStoredSource(created.id());

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
    }

    protected CreateDiaryCommand createCommand(String sourceText) {
        return new CreateDiaryCommand(USER_ID, DIARY_DATE, sourceText, UUID.randomUUID());
    }

    protected UUID insertLegacyDiary(CreateDiaryCommand command) {
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
                "기존 그림일기", "generated/%s.png".formatted(diaryId), serviceClock.instant());
        return diaryId;
    }

    protected void markGenerationSucceeded(DiaryCreationClaim claim) {
        executeUpdate("""
                UPDATE diary_generations
                SET status = 'SUCCEEDED', title = ?, image_object_key = ?, completed_at = ?
                WHERE id = ?
                """, "오늘의 그림일기", "generated/%s.png".formatted(claim.diaryId()), serviceClock.instant(), claim.generationId());
    }

    protected void replaceWithCiphertextFromAnotherDiary(UUID diaryId) {
        String swappedCiphertext = sourceTextCipher.encrypt(UUID.randomUUID(), USER_ID, SOURCE_TEXT);
        executeUpdate("UPDATE diaries SET source_text = ? WHERE id = ?", swappedCiphertext, diaryId);
    }

    protected StoredSource findStoredSource(UUID diaryId) {
        Object[] row = (Object[]) readValue(
                "SELECT source_text, source_text_version FROM diaries WHERE id = ?", diaryId
        );
        return new StoredSource((String) row[0], ((Number) row[1]).intValue());
    }

    protected long countUserDiaries() {
        return ((Number) readValue("SELECT COUNT(*) FROM diaries WHERE user_id = ?", USER_ID)).longValue();
    }

    protected Object readValue(String statement, Object... parameters) {
        return transactionTemplate.execute(status -> createQuery(statement, parameters).getSingleResult());
    }

    protected void executeUpdate(String statement, Object... parameters) {
        transactionTemplate.executeWithoutResult(status -> createQuery(statement, parameters).executeUpdate());
    }

    protected Query createQuery(String statement, Object... parameters) {
        Query query = entityManager.createNativeQuery(statement);
        IntStream.range(0, parameters.length)
                .forEach(index -> query.setParameter(index + 1, parameters[index]));
        return query;
    }

    protected record StoredSource(String text, int version) {
    }

    @FunctionalInterface
    protected interface PendingPreparation {
        void prepare(CreateDiaryCommand command, CreateDiaryResult created) throws SQLException;
    }
}
