package com.harudle.generation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.domain.DiaryGeneration;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.repository.DiaryGenerationRepository;
import com.harudle.generation.repository.GenerationPromptRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class DiaryGenerationFailurePersistenceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DiaryRepository diaryRepository;

    @Autowired
    private DiaryGenerationRepository diaryGenerationRepository;

    @Autowired
    private GenerationPromptRepository generationPromptRepository;

    @Autowired
    private DiaryGenerationCompletionService completionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private GenerationPrompt generationPrompt;

    @BeforeEach
    void setUp() {
        executeUpdate(
                "INSERT INTO users (id, primary_email, name) VALUES (?, ?, ?)",
                USER_ID,
                "harudle@example.com",
                "하루들"
        );
        generationPrompt = generationPromptRepository.saveAndFlush(new GenerationPrompt(
                "스토리보드 프롬프트",
                "이미지 스타일 프롬프트",
                "references/style.png"
        ));
    }

    @AfterEach
    void tearDown() {
        executeUpdate("DELETE FROM users WHERE id = ?", USER_ID);
        generationPromptRepository.deleteAll();
    }

    @Test
    @DisplayName("생성 실패 상태와 일기 폐기를 하나의 트랜잭션으로 저장한다")
    void failGenerationAndDiscardDiaryAtomically() {
        Diary diary = diaryRepository.saveAndFlush(Diary.create(
                USER_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다."
        ));
        DiaryGeneration generation = diaryGenerationRepository.saveAndFlush(DiaryGeneration.start(
                diary.getId(),
                generationPrompt.getId(),
                UUID.randomUUID(),
                "a".repeat(64)
        ));

        completionService.fail(generation.getId(), GenerationErrorCode.AI_PROVIDER_TIMEOUT);

        assertThat(diaryGenerationRepository.findById(generation.getId()))
                .hasValueSatisfying(savedGeneration -> {
                    assertThat(savedGeneration.getStatus()).isEqualTo(GenerationStatus.FAILED);
                    assertThat(savedGeneration.getErrorCode())
                            .isEqualTo(GenerationErrorCode.AI_PROVIDER_TIMEOUT);
                });
        assertThat(diaryRepository.findById(diary.getId()))
                .hasValueSatisfying(savedDiary -> assertThat(savedDiary.isDeleted()).isTrue());
        assertThat(diaryRepository.findActiveSnapshotById(diary.getId())).isEmpty();
        assertThat(diaryRepository.findActiveSnapshotsBetween(
                USER_ID,
                DIARY_DATE.withDayOfMonth(1),
                DIARY_DATE.withDayOfMonth(DIARY_DATE.lengthOfMonth())
        )).isEmpty();
    }

    private void executeUpdate(String statement, Object... parameters) {
        transactionTemplate.executeWithoutResult(status -> {
            Query query = entityManager.createNativeQuery(statement);
            IntStream.range(0, parameters.length)
                    .forEach(index -> query.setParameter(index + 1, parameters[index]));
            query.executeUpdate();
        });
    }
}
