package com.harudle.admin.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.admin.query.AdminGenerationHistoryPage;
import com.harudle.admin.query.AdminGenerationHistorySnapshot;
import com.harudle.admin.repository.AdminGenerationHistoryQueryRepository;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationPrompt;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.repository.GenerationPromptRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class JpaAdminGenerationHistoryQueryRepositoryTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T00:00:00Z");
    private static final UUID FIRST_GENERATION_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001"
    );
    private static final UUID SECOND_GENERATION_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000002"
    );
    private static final UUID THIRD_GENERATION_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000003"
    );
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private AdminGenerationHistoryQueryRepository generationHistoryQueryRepository;

    @Autowired
    private GenerationPromptRepository generationPromptRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private GenerationPrompt generationPrompt;
    private User firstUser;
    private User secondUser;

    @BeforeEach
    void setUp() {
        firstUser = userRepository.saveAndFlush(new User(null, "첫 번째 사용자", CREATED_AT));
        secondUser = userRepository.saveAndFlush(new User(null, "두 번째 사용자", CREATED_AT));
        generationPrompt = generationPromptRepository.saveAndFlush(new GenerationPrompt(
                "생성 이력 테스트 스토리보드",
                "생성 이력 테스트 이미지 스타일",
                "references/admin-history.png"
        ));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM diary_generations");
        jdbcTemplate.update("DELETE FROM diaries");
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", firstUser.getId(), secondUser.getId());
        generationPromptRepository.deleteAll();
    }

    @Test
    @DisplayName("전체 생성 이력을 요청 시각 내림차순과 ID 내림차순으로 조회한다")
    void findsAllHistoryInRequestedAtOrder() {
        insertGeneration(
                FIRST_GENERATION_ID,
                firstUser.getId(),
                CREATED_AT,
                GenerationStatus.FAILED,
                GenerationErrorCode.AI_PROVIDER_ERROR
        );
        insertGeneration(
                SECOND_GENERATION_ID,
                firstUser.getId(),
                CREATED_AT.plusSeconds(1),
                GenerationStatus.SUCCEEDED,
                null
        );
        insertGeneration(
                THIRD_GENERATION_ID,
                secondUser.getId(),
                CREATED_AT.plusSeconds(2),
                GenerationStatus.PROCESSING,
                null
        );

        AdminGenerationHistoryPage result = generationHistoryQueryRepository.search(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                20
        );

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.content())
                .extracting(AdminGenerationHistorySnapshot::id)
                .containsExactly(THIRD_GENERATION_ID, SECOND_GENERATION_ID, FIRST_GENERATION_ID);
        assertThat(result.content().getFirst().userName()).isEqualTo("두 번째 사용자");
    }

    @Test
    @DisplayName("사용자·상태·날짜 필터를 함께 적용한다")
    void appliesCombinedFilters() {
        insertGeneration(
                FIRST_GENERATION_ID,
                firstUser.getId(),
                CREATED_AT,
                GenerationStatus.FAILED,
                GenerationErrorCode.AI_PROVIDER_ERROR
        );
        insertGeneration(
                SECOND_GENERATION_ID,
                firstUser.getId(),
                CREATED_AT.plusSeconds(1),
                GenerationStatus.SUCCEEDED,
                null
        );
        insertGeneration(
                THIRD_GENERATION_ID,
                secondUser.getId(),
                CREATED_AT,
                GenerationStatus.FAILED,
                GenerationErrorCode.IMAGE_STORAGE_ERROR
        );

        AdminGenerationHistoryPage result = generationHistoryQueryRepository.search(
                Optional.of(firstUser.getId()),
                Optional.of(GenerationStatus.FAILED),
                Optional.of(CREATED_AT),
                Optional.of(CREATED_AT.plusSeconds(1)),
                0,
                20
        );

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content())
                .extracting(AdminGenerationHistorySnapshot::id)
                .containsExactly(FIRST_GENERATION_ID);
        assertThat(result.content().getFirst().errorCode())
                .isEqualTo(GenerationErrorCode.AI_PROVIDER_ERROR);
    }

    @Test
    @DisplayName("생성 이력 페이지네이션은 전체 개수와 현재 페이지를 함께 반환한다")
    void paginatesHistory() {
        insertGeneration(
                FIRST_GENERATION_ID,
                firstUser.getId(),
                CREATED_AT,
                GenerationStatus.FAILED,
                GenerationErrorCode.AI_PROVIDER_ERROR
        );
        insertGeneration(
                SECOND_GENERATION_ID,
                firstUser.getId(),
                CREATED_AT.plusSeconds(1),
                GenerationStatus.SUCCEEDED,
                null
        );
        insertGeneration(
                THIRD_GENERATION_ID,
                secondUser.getId(),
                CREATED_AT.plusSeconds(2),
                GenerationStatus.PROCESSING,
                null
        );

        AdminGenerationHistoryPage result = generationHistoryQueryRepository.search(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                1,
                1
        );

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.content())
                .extracting(AdminGenerationHistorySnapshot::id)
                .containsExactly(SECOND_GENERATION_ID);
    }

    private void insertGeneration(
            UUID generationId,
            UUID userId,
            Instant requestedAt,
            GenerationStatus status,
            GenerationErrorCode errorCode
    ) {
        UUID diaryId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO diaries (
                    id, user_id, diary_date, source_text, created_at, updated_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, diaryId, userId, LocalDate.of(2026, 8, 6), "생성 이력 테스트 일기",
                Timestamp.from(requestedAt), Timestamp.from(requestedAt), null);

        boolean completed = status != GenerationStatus.PROCESSING;
        jdbcTemplate.update("""
                INSERT INTO diary_generations (
                    id,
                    diary_id,
                    prompt_id,
                    idempotency_key,
                    request_fingerprint,
                    status,
                    title,
                    image_object_key,
                    error_code,
                    created_at,
                    updated_at,
                    completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, generationId, diaryId, generationPrompt.getId(), UUID.randomUUID(), "a".repeat(64),
                status.name(), status == GenerationStatus.SUCCEEDED ? "생성 이력 테스트 결과" : null,
                status == GenerationStatus.SUCCEEDED ? "generated/admin-history/" + generationId + ".png" : null,
                errorCode == null ? null : errorCode.name(), Timestamp.from(requestedAt),
                Timestamp.from(requestedAt), completed ? Timestamp.from(requestedAt.plusSeconds(1)) : null);
    }
}
