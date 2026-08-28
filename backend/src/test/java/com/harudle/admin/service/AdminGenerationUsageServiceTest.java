package com.harudle.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.repository.GenerationUsageRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class AdminGenerationUsageServiceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate USAGE_DATE = LocalDate.now(SERVICE_ZONE);
    private static final long CONCURRENCY_READY_TIMEOUT_SECONDS = 5L;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private AdminGenerationUsageService adminGenerationUsageService;

    @Autowired
    private GenerationUsageRepository generationUsageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "INSERT INTO users (id, primary_email, name) VALUES (?, ?, ?)",
                USER_ID,
                "usage-race@example.com",
                "사용량 경합 사용자"
        );
        jdbcTemplate.update(
                "UPDATE users SET daily_generation_limit = ? WHERE id = ?",
                5,
                USER_ID
        );
        jdbcTemplate.update("""
                INSERT INTO daily_generation_usage (
                    user_id,
                    usage_date,
                    used_count,
                    limit_count
                )
                VALUES (?, ?, 5, 5)
                """, USER_ID, USAGE_DATE);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM daily_generation_usage WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", USER_ID);
    }

    @Test
    @DisplayName("한도 변경과 사용량 초기화는 사용자 행 잠금으로 직렬화된다")
    void serializesLimitChangeAndReset() throws Exception {
        CountDownLatch limitChangeHolding = new CountDownLatch(1);
        CountDownLatch releaseLimitChange = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> limitChangeFuture = submitLimitChange(
                    executor,
                    limitChangeHolding,
                    releaseLimitChange
            );
            awaitLatch(limitChangeHolding);

            Future<GenerationUsage> resetFuture = executor.submit(
                    () -> adminGenerationUsageService.reset(USER_ID)
            );

            assertThatThrownBy(() -> resetFuture.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseLimitChange.countDown();
            awaitFuture(limitChangeFuture);
            assertThat(resetFuture.get(
                    CONCURRENCY_READY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            )).isEqualTo(new GenerationUsage(USAGE_DATE, 0, 3));
        } finally {
            releaseLimitChange.countDown();
        }

        assertThat(findUserDailyGenerationLimit()).isEqualTo(3);
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 0, 3));
    }

    private Future<?> submitLimitChange(
            ExecutorService executor,
            CountDownLatch limitChangeHolding,
            CountDownLatch releaseLimitChange
    ) {
        return executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            adminGenerationUsageService.changeLimit(USER_ID, 3);
            limitChangeHolding.countDown();
            awaitLatch(releaseLimitChange);
        }));
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(CONCURRENCY_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 실행 작업이 제한 시간 안에 준비되지 않았습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 실행 작업 대기 중 인터럽트가 발생했습니다.", exception);
        }
    }

    private void awaitFuture(Future<?> future) {
        try {
            future.get(CONCURRENCY_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("한도 변경 결과를 가져오지 못했습니다.", exception);
        }
    }

    private int findUserDailyGenerationLimit() {
        return jdbcTemplate.queryForObject(
                "SELECT daily_generation_limit FROM users WHERE id = ?",
                Integer.class,
                USER_ID
        );
    }
}
