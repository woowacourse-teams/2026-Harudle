package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.DiaryCreationClaim;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.repository.GenerationUsageRepository;
import com.harudle.generation.service.exception.DailyGenerationLimitExceededException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class DiaryPersistenceIntegrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 8, 6);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GenerationUsageRepository generationUsageRepository;

    @Autowired
    private DiaryCreationTransactionService transactionService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                INSERT INTO users (id, primary_email, name)
                VALUES (?, ?, ?)
                """, USER_ID, "harudle@example.com", "하루들");
        jdbcTemplate.update("""
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
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", USER_ID);
        jdbcTemplate.update(
                "DELETE FROM generation_prompts WHERE image_asset_object_key = ?",
                "references/style.png"
        );
    }

    @Test
    @DisplayName("일기와 처리 중 생성 기록 및 사용량을 하나의 트랜잭션으로 선점한다")
    void claimDiaryCreationAtomically() {
        CreateDiaryCommand command = new CreateDiaryCommand(
                USER_ID,
                USAGE_DATE,
                "오늘 친구와 카페에 갔다.",
                IDEMPOTENCY_KEY
        );

        DiaryCreationClaim claim = transactionService.claim(command, true);

        assertThat(claim.newlyCreated()).isTrue();
        assertThat(claim.generationStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(claim.usage().usedCount()).isEqualTo(1);
        assertThat(countRows("diaries")).isEqualTo(1);
        assertThat(countRows("comic_generations")).isEqualTo(1);
        assertThat(countRows("daily_generation_usage")).isEqualTo(1);
    }

    @Test
    @DisplayName("생성 한도 초과 시 일기와 생성 기록 선점을 모두 롤백한다")
    void rollbackClaimWhenDailyLimitIsExceeded() {
        jdbcTemplate.update("""
                INSERT INTO daily_generation_usage (
                    user_id,
                    usage_date,
                    used_count,
                    limit_count
                )
                VALUES (?, ?, 3, 3)
                """, USER_ID, USAGE_DATE);
        CreateDiaryCommand command = new CreateDiaryCommand(
                USER_ID,
                USAGE_DATE,
                "오늘 친구와 카페에 갔다.",
                IDEMPOTENCY_KEY
        );

        assertThatThrownBy(() -> transactionService.claim(command, true))
                .isInstanceOf(DailyGenerationLimitExceededException.class);
        assertThat(countRows("diaries")).isZero();
        assertThat(countRows("comic_generations")).isZero();
        assertThat(countRows("daily_generation_usage")).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 생성 요청은 일일 제한 횟수까지만 사용량을 증가시킨다")
    void incrementUsageAtomicallyUnderConcurrency() throws InterruptedException {
        List<Callable<Optional<GenerationUsage>>> increments = IntStream.range(0, 10)
                .mapToObj(index -> (Callable<Optional<GenerationUsage>>) () ->
                        generationUsageRepository.incrementWithinLimit(USER_ID, USAGE_DATE))
                .toList();

        List<Optional<GenerationUsage>> results;
        try (var executor = Executors.newFixedThreadPool(10)) {
            results = executor.invokeAll(increments).stream()
                    .map(future -> getResult(future))
                    .toList();
        }

        assertThat(results).filteredOn(Optional::isPresent).hasSize(3);
        List<Integer> usedCounts = results.stream()
                .flatMap(Optional::stream)
                .map(GenerationUsage::usedCount)
                .toList();
        assertThat(usedCounts)
                .containsExactlyInAnyOrder(1, 2, 3);
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 3, 3));
    }

    private Optional<GenerationUsage> getResult(Future<Optional<GenerationUsage>> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "동시 사용량 증가 결과를 가져오지 못했습니다.",
                    exception
            );
        }
    }

    private int countRows(String tableName) {
        String query = "SELECT COUNT(*) FROM " + tableName;
        Integer count = jdbcTemplate.queryForObject(query, Integer.class);
        return count == null ? 0 : count;
    }
}
