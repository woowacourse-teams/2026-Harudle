package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.DiaryDayResult;
import com.harudle.diary.service.dto.DiaryTimelineResult;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.repository.GenerationPromptRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
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
class DiaryQueryPersistenceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID OTHER_USER_ID = UUID.fromString("fcd41d4a-2cce-4f28-bdb7-524d00ef4da6");
    private static final UUID FIRST_DIARY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_DIARY_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FAILED_DIARY_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID DELETED_DIARY_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID OTHER_USERS_DIARY_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID OUTSIDE_MONTH_DIARY_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID LATEST_DATE_DIARY_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T12:00:00Z");
    private static final Instant LATER_CREATED_AT = CREATED_AT.plusSeconds(1);

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private GenerationPromptRepository generationPromptRepository;

    @Autowired
    private DiaryRepository diaryRepository;

    @Autowired
    private DiaryQueryService diaryQueryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private GenerationPrompt generationPrompt;

    @BeforeEach
    void setUp() {
        insertUser(USER_ID, "harudle@example.com");
        insertUser(OTHER_USER_ID, "other@example.com");
        generationPrompt = generationPromptRepository.saveAndFlush(new GenerationPrompt(
                "스토리보드 프롬프트",
                "이미지 스타일 프롬프트",
                "references/style.png"
        ));
    }

    @AfterEach
    void tearDown() {
        executeUpdate("DELETE FROM diary_generations");
        executeUpdate("DELETE FROM diaries");
        executeUpdate("DELETE FROM users WHERE id IN (?, ?)", USER_ID, OTHER_USER_ID);
        generationPromptRepository.deleteAll();
    }

    @Test
    @DisplayName("월간 조회는 본인의 성공한 활성 일기를 최신순으로 반환한다")
    void findMonthlySuccessfulActiveDiariesInStableOrder() {
        insertDiary(FIRST_DIARY_ID, USER_ID, DIARY_DATE, CREATED_AT, null);
        insertDiary(SECOND_DIARY_ID, USER_ID, DIARY_DATE, LATER_CREATED_AT, null);
        insertDiary(FAILED_DIARY_ID, USER_ID, DIARY_DATE, LATER_CREATED_AT, null);
        insertDiary(DELETED_DIARY_ID, USER_ID, DIARY_DATE, CREATED_AT, LATER_CREATED_AT);
        insertDiary(OTHER_USERS_DIARY_ID, OTHER_USER_ID, DIARY_DATE, CREATED_AT, null);
        insertDiary(OUTSIDE_MONTH_DIARY_ID, USER_ID, DIARY_DATE.plusMonths(1), CREATED_AT, null);
        insertDiary(LATEST_DATE_DIARY_ID, USER_ID, DIARY_DATE.plusDays(1), CREATED_AT, null);
        insertSuccessfulGeneration(FIRST_DIARY_ID, "첫 번째 일기");
        insertSuccessfulGeneration(SECOND_DIARY_ID, "두 번째 일기");
        insertFailedGeneration(FAILED_DIARY_ID);
        insertSuccessfulGeneration(DELETED_DIARY_ID, "삭제된 일기");
        insertSuccessfulGeneration(OTHER_USERS_DIARY_ID, "다른 사용자 일기");
        insertSuccessfulGeneration(OUTSIDE_MONTH_DIARY_ID, "다른 달 일기");
        insertSuccessfulGeneration(LATEST_DATE_DIARY_ID, "최신 날짜 일기");

        assertThat(diaryRepository.findMonthlySnapshots(
                USER_ID,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31)
        )).extracting(snapshot -> snapshot.id())
                .containsExactly(
                        LATEST_DATE_DIARY_ID,
                        FAILED_DIARY_ID,
                        SECOND_DIARY_ID,
                        FIRST_DIARY_ID
                );

        DiaryTimelineResult timeline = diaryQueryService.getTimeline(USER_ID, 2026, 8);
        assertThat(timeline.days())
                .extracting(DiaryDayResult::date)
                .isSortedAccordingTo(Comparator.reverseOrder());
        DiaryDayResult latestDate = findDay(timeline, DIARY_DATE.plusDays(1));
        DiaryDayResult diaryDate = findDay(timeline, DIARY_DATE);

        assertThat(latestDate.items())
                .extracting(item -> item.id())
                .containsExactly(LATEST_DATE_DIARY_ID);
        assertThat(diaryDate.items())
                .extracting(item -> item.id())
                .containsExactly(SECOND_DIARY_ID, FIRST_DIARY_ID);
    }

    private DiaryDayResult findDay(DiaryTimelineResult timeline, LocalDate date) {
        return timeline.days().stream()
                .filter(day -> day.date().equals(date))
                .findFirst()
                .orElseThrow();
    }

    private void insertUser(UUID userId, String email) {
        executeUpdate(
                "INSERT INTO users (id, primary_email, name) VALUES (?, ?, ?)",
                userId,
                email,
                "하루들"
        );
    }

    private void insertDiary(
            UUID diaryId,
            UUID userId,
            LocalDate diaryDate,
            Instant createdAt,
            Instant deletedAt
    ) {
        executeUpdate("""
                INSERT INTO diaries (
                    id,
                    user_id,
                    diary_date,
                    source_text,
                    created_at,
                    updated_at,
                    deleted_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, diaryId, userId, diaryDate, "오늘의 일기", createdAt, createdAt, deletedAt);
    }

    private void insertSuccessfulGeneration(UUID diaryId, String title) {
        executeUpdate("""
                INSERT INTO diary_generations (
                    id,
                    diary_id,
                    prompt_id,
                    idempotency_key,
                    request_fingerprint,
                    status,
                    title,
                    image_object_key,
                    created_at,
                    updated_at,
                    completed_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                diaryId,
                generationPrompt.getId(),
                UUID.randomUUID(),
                "a".repeat(64),
                "SUCCEEDED",
                title,
                "generated/%s.png".formatted(diaryId),
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
    }

    private void insertFailedGeneration(UUID diaryId) {
        executeUpdate("""
                INSERT INTO diary_generations (
                    id,
                    diary_id,
                    prompt_id,
                    idempotency_key,
                    request_fingerprint,
                    status,
                    error_code,
                    created_at,
                    updated_at,
                    completed_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                diaryId,
                generationPrompt.getId(),
                UUID.randomUUID(),
                "a".repeat(64),
                "FAILED",
                "AI_PROVIDER_ERROR",
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
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
