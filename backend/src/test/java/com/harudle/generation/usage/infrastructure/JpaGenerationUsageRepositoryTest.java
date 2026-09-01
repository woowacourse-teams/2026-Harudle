package com.harudle.generation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.repository.GenerationUsageRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class JpaGenerationUsageRepositoryTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 8, 6);
    private static final int CONCURRENT_REQUEST_COUNT = 10;
    private static final long CONCURRENCY_READY_TIMEOUT_SECONDS = 5L;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private GenerationUsageRepository generationUsageRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        executeUpdate(
                "INSERT INTO users (id, primary_email, name) VALUES (?, ?, ?)",
                USER_ID,
                "harudle@example.com",
                "하루들"
        );
    }

    @AfterEach
    void tearDown() {
        executeUpdate("DELETE FROM daily_generation_usage WHERE user_id = ?", USER_ID);
        executeUpdate("DELETE FROM users WHERE id = ?", USER_ID);
    }

    @Test
    @DisplayName("동시 생성 요청은 일일 제한 횟수까지만 사용량을 증가시킨다")
    void incrementUsageAtomicallyUnderConcurrency() throws InterruptedException {
        List<Optional<GenerationUsage>> results = executeConcurrently(incrementTasks());

        assertThat(results).filteredOn(Optional::isPresent).hasSize(3);
        assertThat(results.stream()
                .flatMap(Optional::stream)
                .map(GenerationUsage::usedCount))
                .containsExactlyInAnyOrder(1, 2, 3);
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 3, 3));
    }

    @Test
    @DisplayName("기존 사용량의 남은 횟수가 하나면 동시 요청 중 하나만 증가한다")
    void incrementExistingUsageAtomicallyUnderConcurrency() throws InterruptedException {
        executeUpdate("""
                INSERT INTO daily_generation_usage (
                    user_id,
                    usage_date,
                    used_count,
                    limit_count
                )
                VALUES (?, ?, 2, 3)
                """, USER_ID, USAGE_DATE);

        List<Optional<GenerationUsage>> results = executeConcurrently(incrementTasks());

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(results).filteredOn(Optional::isPresent)
                .allSatisfy(result -> assertThat(result)
                        .contains(new GenerationUsage(USAGE_DATE, 3, 3)));
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 3, 3));
    }

    @Test
    @DisplayName("같은 트랜잭션에서 사용량을 연속 증가하고 최신 값을 조회한다")
    void incrementAndFindUsageWithinSameTransaction() {
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(generationUsageRepository.tryIncrementWithinLimit(USER_ID, USAGE_DATE))
                    .contains(new GenerationUsage(USAGE_DATE, 1, 3));
            assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                    .contains(new GenerationUsage(USAGE_DATE, 1, 3));
            assertThat(generationUsageRepository.tryIncrementWithinLimit(USER_ID, USAGE_DATE))
                    .contains(new GenerationUsage(USAGE_DATE, 2, 3));
            assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                    .contains(new GenerationUsage(USAGE_DATE, 2, 3));
        });
    }

    @Test
    @DisplayName("첫 사용량 행을 생성할 때 사용자의 현재 한도를 스냅샷한다")
    void snapshotsCurrentUserLimitWhenCreatingUsage() {
        executeUpdate(
                "UPDATE users SET daily_generation_limit = ? WHERE id = ?",
                5,
                USER_ID
        );

        assertThat(generationUsageRepository.tryIncrementWithinLimit(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 1, 5));
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 1, 5));
    }

    @Test
    @DisplayName("첫 사용량 생성과 한도 변경은 사용자 행 잠금으로 직렬화된다")
    void serializesFirstUsageCreationAndLimitChange() throws Exception {
        CountDownLatch incrementHolding = new CountDownLatch(1);
        CountDownLatch releaseIncrement = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<GenerationUsage>> incrementFuture = executor.submit(() ->
                    transactionTemplate.execute(status -> {
                        Optional<GenerationUsage> usage = generationUsageRepository
                                .tryIncrementWithinLimit(USER_ID, USAGE_DATE);
                        incrementHolding.countDown();
                        awaitLatch(releaseIncrement);
                        return usage;
                    })
            );

            try {
                assertThat(incrementHolding.await(
                        CONCURRENCY_READY_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                )).isTrue();

                Future<Integer> limitUpdateFuture = executor.submit(() ->
                        transactionTemplate.execute(status -> {
                            int updatedRows = generationUsageRepository
                                    .updateLimitCount(USER_ID, USAGE_DATE, 5);
                            executeUpdate(
                                    "UPDATE users SET daily_generation_limit = ? WHERE id = ?",
                                    5,
                                    USER_ID
                            );
                            return updatedRows;
                        })
                );

                assertThatThrownBy(() -> limitUpdateFuture.get(500, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);

                releaseIncrement.countDown();
                assertThat(incrementFuture.get(
                        CONCURRENCY_READY_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                )).contains(new GenerationUsage(USAGE_DATE, 1, 3));
                assertThat(limitUpdateFuture.get(
                        CONCURRENCY_READY_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                )).isEqualTo(1);
            } finally {
                releaseIncrement.countDown();
            }
        }

        assertThat(findUserDailyGenerationLimit()).isEqualTo(5);
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 1, 5));
    }

    @Test
    @DisplayName("오늘 사용량 이상인 한도로만 갱신한다")
    void updatesLimitCountOnlyWhenNotBelowUsedCount() {
        executeUpdate("""
                INSERT INTO daily_generation_usage (
                    user_id,
                    usage_date,
                    used_count,
                    limit_count
                )
                VALUES (?, ?, 2, 3)
                """, USER_ID, USAGE_DATE);

        assertThat(generationUsageRepository.updateLimitCount(USER_ID, USAGE_DATE, 1))
                .isZero();
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 2, 3));

        assertThat(generationUsageRepository.updateLimitCount(USER_ID, USAGE_DATE, 2))
                .isEqualTo(1);
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 2, 2));

        assertThat(generationUsageRepository.updateLimitCount(USER_ID, USAGE_DATE, 5))
                .isEqualTo(1);
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 2, 5));
    }

    @Test
    @DisplayName("1 미만인 오늘 사용량 한도 변경은 거부한다")
    void rejectsLimitCountBelowOne() {
        assertThatThrownBy(() -> generationUsageRepository.updateLimitCount(USER_ID, USAGE_DATE, 0))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessage("일일 생성 한도는 1 이상이어야 합니다.");
    }

    @Test
    @DisplayName("사용량 스냅샷의 제한 횟수 0은 DB에서 거부한다")
    void rejectsZeroLimitCountAtDatabase() {
        assertThatThrownBy(() -> executeUpdate("""
                INSERT INTO daily_generation_usage (
                    user_id,
                    usage_date,
                    used_count,
                    limit_count
                )
                VALUES (?, ?, 0, 0)
                """, USER_ID, USAGE_DATE))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    @DisplayName("현재 사용량 이내의 횟수를 원자적으로 복구한다")
    void restoresUsageAtomically() {
        executeUpdate("""
                INSERT INTO daily_generation_usage (
                    user_id,
                    usage_date,
                    used_count,
                    limit_count
                )
                VALUES (?, ?, 3, 3)
                """, USER_ID, USAGE_DATE);

        assertThat(generationUsageRepository.tryRestore(USER_ID, USAGE_DATE, 2))
                .contains(new GenerationUsage(USAGE_DATE, 1, 3));
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 1, 3));
    }

    @Test
    @DisplayName("사용량 행이 없거나 복구 수량이 많으면 복구하지 않는다")
    void rejectsInvalidRestoreWithoutChangingUsage() {
        assertThat(generationUsageRepository.tryRestore(USER_ID, USAGE_DATE, 1)).isEmpty();
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE)).isEmpty();

        executeUpdate("""
                INSERT INTO daily_generation_usage (
                    user_id,
                    usage_date,
                    used_count,
                    limit_count
                )
                VALUES (?, ?, 1, 3)
                """, USER_ID, USAGE_DATE);

        assertThat(generationUsageRepository.tryRestore(USER_ID, USAGE_DATE, 2)).isEmpty();
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 1, 3));
    }

    @Test
    @DisplayName("동시 복구 요청은 현재 사용량을 초과하지 않는다")
    void restoreUsageAtomicallyUnderConcurrency() throws InterruptedException {
        executeUpdate("""
                INSERT INTO daily_generation_usage (
                    user_id,
                    usage_date,
                    used_count,
                    limit_count
                )
                VALUES (?, ?, 3, 3)
                """, USER_ID, USAGE_DATE);

        List<Optional<GenerationUsage>> results = executeConcurrently(restoreTasks());

        assertThat(results).filteredOn(Optional::isPresent).hasSize(3);
        assertThat(results.stream()
                .flatMap(Optional::stream)
                .map(GenerationUsage::usedCount))
                .containsExactlyInAnyOrder(2, 1, 0);
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 0, 3));
    }

    @Test
    @DisplayName("사용량을 0으로 초기화하고 전달받은 한도로 스냅샷을 갱신한다")
    void resetsUsageAndUpdatesLimitSnapshot() {
        executeUpdate("""
                INSERT INTO daily_generation_usage (
                    user_id,
                    usage_date,
                    used_count,
                    limit_count
                )
                VALUES (?, ?, 3, 5)
                """, USER_ID, USAGE_DATE);

        assertThat(generationUsageRepository.tryReset(USER_ID, USAGE_DATE, 3))
                .contains(new GenerationUsage(USAGE_DATE, 0, 3));
        assertThat(generationUsageRepository.tryReset(USER_ID, USAGE_DATE, 3))
                .contains(new GenerationUsage(USAGE_DATE, 0, 3));
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE))
                .contains(new GenerationUsage(USAGE_DATE, 0, 3));
    }

    @Test
    @DisplayName("사용량 행이 없으면 초기화하지 않고 빈 결과를 반환한다")
    void returnsEmptyWhenResetUsageDoesNotExist() {
        assertThat(generationUsageRepository.tryReset(USER_ID, USAGE_DATE, 3)).isEmpty();
        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE)).isEmpty();
    }

    @Test
    @DisplayName("외부 트랜잭션이 롤백되면 사용량 증가도 함께 롤백한다")
    void rollbackUsageIncrementWithOuterTransaction() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            generationUsageRepository.tryIncrementWithinLimit(USER_ID, USAGE_DATE);
            throw new IllegalStateException("트랜잭션 롤백 검증");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(generationUsageRepository.find(USER_ID, USAGE_DATE)).isEmpty();
    }

    private List<Callable<Optional<GenerationUsage>>> incrementTasks() {
        return createTasks(
                () -> () -> generationUsageRepository.tryIncrementWithinLimit(USER_ID, USAGE_DATE)
        );
    }

    private List<Callable<Optional<GenerationUsage>>> restoreTasks() {
        return createTasks(
                () -> () -> generationUsageRepository.tryRestore(USER_ID, USAGE_DATE, 1)
        );
    }

    private <T> List<Callable<T>> createTasks(Supplier<Callable<T>> taskFactory) {
        return IntStream.range(0, CONCURRENT_REQUEST_COUNT)
                .mapToObj(index -> taskFactory.get())
                .toList();
    }

    private <T> List<T> executeConcurrently(List<Callable<T>> tasks) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> synchronizedTasks = tasks.stream()
                .map(task -> (Callable<T>) () -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                })
                .toList();
        try (var executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = synchronizedTasks.stream()
                    .map(executor::submit)
                    .toList();
            if (!ready.await(CONCURRENCY_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                start.countDown();
                throw new IllegalStateException("동시 실행 작업이 제한 시간 안에 준비되지 않았습니다.");
            }
            start.countDown();
            return futures.stream()
                    .map(this::getResult)
                    .toList();
        }
    }

    private <T> T getResult(Future<T> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new IllegalStateException("동시 사용량 증가 결과를 가져오지 못했습니다.", exception);
        }
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(CONCURRENCY_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 실행 작업이 제한 시간 안에 해제되지 않았습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 실행 작업 대기 중 인터럽트가 발생했습니다.", exception);
        }
    }

    private int findUserDailyGenerationLimit() {
        return transactionTemplate.execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT daily_generation_limit FROM users WHERE id = ?")
                .setParameter(1, USER_ID)
                .getSingleResult()).intValue());
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
