package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.service.exception.DailyGenerationLimitExceededException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(DiaryCreationPersistenceTest.FixedClockConfiguration.class)
class DiaryCreationPersistenceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DiaryCreationTransactionService transactionService;

    @BeforeEach
    void setUp() {
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

    @Test
    @DisplayName("일기와 처리 중 생성 기록 및 사용량을 하나의 트랜잭션으로 선점한다")
    void claimDiaryCreationAtomically() {
        DiaryCreationClaim claim = transactionService.claim(createCommand(), true);

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
        executeUpdate("""
                INSERT INTO daily_generation_usage (
                    user_id,
                    usage_date,
                    used_count,
                    limit_count
                )
                VALUES (?, ?, 3, 3)
                """, USER_ID, DIARY_DATE);

        assertThatThrownBy(() -> transactionService.claim(createCommand(), true))
                .isInstanceOf(DailyGenerationLimitExceededException.class);
        assertThat(countRows("diaries")).isZero();
        assertThat(countRows("comic_generations")).isZero();
        assertThat(countRows("daily_generation_usage")).isEqualTo(1);
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
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-08-06T12:00:00Z"), ZoneOffset.UTC);
        }
    }
}
