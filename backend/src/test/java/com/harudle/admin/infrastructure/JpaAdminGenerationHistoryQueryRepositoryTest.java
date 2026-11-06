package com.harudle.admin.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.admin.query.AdminGenerationHistorySnapshot;
import com.harudle.admin.repository.AdminGenerationHistoryQueryRepository;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.StoryPanel;
import com.harudle.generation.diary.domain.Storyboard;
import com.harudle.generation.diary.domain.StoryboardProviderInfo;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.prompt.domain.GenerationPrompt;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import com.harudle.support.ServiceIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JpaAdminGenerationHistoryQueryRepositoryTest extends ServiceIntegrationTestSupport {

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
    @Autowired
    private AdminGenerationHistoryQueryRepository generationHistoryQueryRepository;

    @Autowired
    private DiaryGenerationRepository diaryGenerations;

    @Autowired
    private GenerationPromptRepository generationPromptRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @Test
    void backupCursorKeepsEqualTimestampTargetsAndExcludesOtherEnvironmentStatusAndFutureCompletion() {
        UUID future = new UUID(0, 4);
        UUID development = new UUID(0, 5);
        UUID failed = new UUID(0, 6);
        for (UUID id : List.of(FIRST_GENERATION_ID, SECOND_GENERATION_ID, THIRD_GENERATION_ID, future, development)) {
            Instant requestedAt = id.equals(future) ? CREATED_AT.plusSeconds(10) : CREATED_AT;
            insertGeneration(id, firstUser.getId(), requestedAt, GenerationStatus.SUCCEEDED, null);
            jdbcTemplate.update("UPDATE diary_generations SET image_object_key = ? WHERE id = ?",
                    "harudle/generated/diary-images/" + (id.equals(development) ? "dev/" : "prod/") + id + "/image.png", id);
        }
        insertGeneration(failed, firstUser.getId(), CREATED_AT, GenerationStatus.FAILED, GenerationErrorCode.AI_PROVIDER_ERROR);
        String pattern = "harudle/generated/diary-images/prod/%";
        Instant cutoff = CREATED_AT.plusSeconds(1);
        var first = diaryGenerations.findImageBackupTargets(GenerationStatus.SUCCEEDED, cutoff, pattern, PageRequest.of(0, 2));
        assertThat(first).extracting(target -> target.generationId()).containsExactly(FIRST_GENERATION_ID, SECOND_GENERATION_ID);
        var cursor = first.getLast();
        var next = diaryGenerations.findImageBackupTargetsAfter(GenerationStatus.SUCCEEDED, cutoff, pattern,
                cursor.completedAt(), cursor.generationId(), PageRequest.of(0, 2));
        assertThat(next).extracting(target -> target.generationId()).containsExactly(THIRD_GENERATION_ID);
        var last = next.getLast();
        assertThat(diaryGenerations.findImageBackupTargetsAfter(GenerationStatus.SUCCEEDED, cutoff, pattern,
                last.completedAt(), last.generationId(), PageRequest.of(0, 2))).isEmpty();
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

        var result = generationHistoryQueryRepository.search(
                null,
                null,
                null,
                null,
                PageRequest.of(0, 20)
        );

        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent())
                .extracting(AdminGenerationHistorySnapshot::id)
                .containsExactly(THIRD_GENERATION_ID, SECOND_GENERATION_ID, FIRST_GENERATION_ID);
        assertThat(result.getContent().getFirst().userName()).isEqualTo("두 번째 사용자");
        assertThat(result.getContent().getFirst().storyboardProvider()).isNull();
        assertThat(result.getContent().getFirst().storyboardModel()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"SUCCEEDED", "FAILED"})
    @DisplayName("성공 또는 이미지 단계 실패의 Provider와 모델을 JPA로 저장하고 관리자 이력에서 조회한다")
    void persistAndProjectStoryboardProviderInfo(GenerationStatus status) {
        insertGeneration(FIRST_GENERATION_ID, firstUser.getId(), CREATED_AT, GenerationStatus.PROCESSING, null);
        var generation = diaryGenerations.findById(FIRST_GENERATION_ID).orElseThrow();
        var info = new StoryboardProviderInfo("qwen", "qwen3.8-max");
        if (status == GenerationStatus.SUCCEEDED) {
            var storyboard = new Storyboard("테스트 결과", "같은 캐릭터", IntStream.rangeClosed(1, 4)
                    .mapToObj(number -> new StoryPanel(number, "캡션 " + number,
                            "장면", "등장인물", "감정", List.of())).toList());
            generation.succeed(storyboard, "generated/provider-test.png", null, info, CREATED_AT.plusSeconds(1));
        } else {
            generation.fail(GenerationErrorCode.IMAGE_STORAGE_ERROR, info, CREATED_AT.plusSeconds(1));
        }
        diaryGenerations.saveAndFlush(generation);

        var result = generationHistoryQueryRepository.search(null, status, null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().storyboardProvider()).isEqualTo("qwen");
        assertThat(result.getContent().getFirst().storyboardModel()).isEqualTo("qwen3.8-max");
        assertThat(result.getContent().getFirst().status()).isEqualTo(status);
    }

    @Test
    @DisplayName("Provider와 모델 중 하나만 저장하는 부분 기록은 DB 제약으로 거절한다")
    void rejectIncompleteProviderInfoAtDatabaseBoundary() {
        insertGeneration(FIRST_GENERATION_ID, firstUser.getId(), CREATED_AT, GenerationStatus.PROCESSING, null);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE diary_generations SET storyboard_provider = ? WHERE id = ?", "qwen", FIRST_GENERATION_ID))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_diary_generations_storyboard_provider_info");
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

        var result = generationHistoryQueryRepository.search(
                firstUser.getId(),
                GenerationStatus.FAILED,
                CREATED_AT,
                CREATED_AT.plusSeconds(1),
                PageRequest.of(0, 20)
        );

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent())
                .extracting(AdminGenerationHistorySnapshot::id)
                .containsExactly(FIRST_GENERATION_ID);
        assertThat(result.getContent().getFirst().errorCode())
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

        var result = generationHistoryQueryRepository.search(
                null,
                null,
                null,
                null,
                PageRequest.of(1, 1)
        );

        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent())
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
