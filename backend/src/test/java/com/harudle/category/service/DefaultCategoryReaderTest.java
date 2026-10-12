package com.harudle.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.category.domain.Category;
import com.harudle.category.query.CategorySnapshot;
import com.harudle.category.repository.CategoryRepository;
import com.harudle.category.service.exception.CategoryInactiveException;
import com.harudle.category.service.exception.CategoryNotFoundException;
import com.harudle.category.service.port.CategoryReader;
import jakarta.persistence.EntityManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DefaultCategoryReader.class, CategoryUpdateService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DefaultCategoryReaderTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired
    private CategoryReader reader;

    @Autowired
    private CategoryUpdateService categoryUpdateService;

    @Autowired
    private CategoryRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private long dailyId;
    private long wootechId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("UPDATE categories SET is_active = TRUE");
        dailyId = categoryId("일상");
        wootechId = categoryId("우테코");
    }

    @ParameterizedTest
    @CsvSource({"일상, 0", "우테코, 1"})
    @DisplayName("일상과 우테코 카테고리는 같은 ID 조회 규칙으로 읽는다")
    void readsCategoryById(String name, int sortOrder) {
        long id = categoryId(name);

        assertThat(reader.get(id)).isEqualTo(new CategoryReader.Category(id, name, sortOrder, true));
    }

    @Test
    @DisplayName("비활성 카테고리도 기존 피드에 표시할 수 있도록 조회한다")
    void readsInactiveCategory() {
        deactivate(wootechId);

        assertThat(reader.get(wootechId))
                .isEqualTo(new CategoryReader.Category(wootechId, "우테코", 1, false));
    }

    @Test
    @DisplayName("존재하지 않는 카테고리 조회는 찾을 수 없음 예외를 발생시킨다")
    void rejectsMissingCategory() {
        assertThatThrownBy(() -> reader.get(Long.MAX_VALUE)).isInstanceOf(CategoryNotFoundException.class);
    }

    @ParameterizedTest
    @CsvSource({"일상, 0", "우테코, 1"})
    @DisplayName("활성 카테고리는 종류에 따른 추가 제한 없이 게시에 사용할 수 있다")
    void allowsPublicationForActiveCategory(String name, int sortOrder) {
        long id = categoryId(name);

        CategoryReader.Category category = transaction.execute(status -> reader.lockActiveForPublication(id));

        assertThat(category).isEqualTo(new CategoryReader.Category(id, name, sortOrder, true));
    }

    @Test
    @DisplayName("없는 카테고리는 게시할 수 없다")
    void rejectsPublicationForMissingCategory() {
        assertThatThrownBy(() -> transaction.execute(status -> reader.lockActiveForPublication(Long.MAX_VALUE)))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    @DisplayName("비활성 카테고리는 조회할 수 있지만 새 피드를 게시할 수 없다")
    void rejectsPublicationForInactiveCategory() {
        deactivate(wootechId);

        assertThatThrownBy(() -> transaction.execute(status -> reader.lockActiveForPublication(wootechId)))
                .isInstanceOf(CategoryInactiveException.class);
        assertThat(reader.get(wootechId).active()).isFalse();
    }

    @Test
    @DisplayName("호출자의 트랜잭션이 없으면 게시 검증과 저장 사이의 잠금을 보장할 수 없어 거부한다")
    void requiresCallerTransaction() {
        assertThatThrownBy(() -> reader.lockActiveForPublication(dailyId))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.findByIdForShare(dailyId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("반복 조회와 게시 검증은 카테고리 정보와 수정 시각을 변경하지 않는다")
    void preservesCategoryOnReadAndPublicationCheck() {
        var before = jdbc.queryForMap("SELECT * FROM categories WHERE id = ?", dailyId);

        reader.get(dailyId);
        reader.get(dailyId);
        transaction.executeWithoutResult(status -> reader.lockActiveForPublication(dailyId));

        assertThat(jdbc.queryForMap("SELECT * FROM categories WHERE id = ?", dailyId)).isEqualTo(before);
    }

    @Test
    @DisplayName("새 카테고리가 추가되어도 이름별 분기 없이 ID로 조회하고 게시를 검증한다")
    void supportsAdditionalCategory() {
        Long id = jdbc.queryForObject("""
                INSERT INTO categories (name, sort_order) VALUES ('운동', 2) RETURNING id
                """, Long.class);
        try {
            assertThat(reader.get(id)).isEqualTo(new CategoryReader.Category(id, "운동", 2, true));
            CategoryReader.Category category = transaction.execute(status -> reader.lockActiveForPublication(id));
            assertThat(category).isEqualTo(reader.get(id));
        } finally {
            jdbc.update("DELETE FROM categories WHERE id = ?", id);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("게시 검증이 반환되어도 관리자 변경은 대기하고 호출자 커밋 또는 롤백 후에 가능하다")
    void holdsLockUntilCallerTransactionEnds(boolean rollback) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                reader.lockActiveForPublication(dailyId);
                var update = executor.submit(() -> transaction.executeWithoutResult(updateStatus -> {
                    jdbc.execute("SET LOCAL lock_timeout = '500ms'");
                    deactivate(dailyId);
                }));

                assertThatThrownBy(() -> update.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(DataAccessException.class)
                        .hasStackTraceContaining("lock timeout");
                assertThat(reader.get(dailyId).active()).isTrue();
                if (rollback) {
                    status.setRollbackOnly();
                }
            });

            deactivate(dailyId);
            assertThat(reader.get(dailyId).active()).isFalse();
        }
    }

    @Test
    @DisplayName("동일 카테고리의 여러 게시 요청은 공유 잠금을 함께 사용할 수 있다")
    void allowsConcurrentPublicationChecks() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                reader.lockActiveForPublication(dailyId);
                var second = executor.submit(() -> transaction.execute(secondStatus -> {
                    jdbc.execute("SET LOCAL lock_timeout = '500ms'");
                    return reader.lockActiveForPublication(dailyId);
                }));

                assertThat(result(second)).isEqualTo(reader.get(dailyId));
            });
        }
    }

    @Test
    @DisplayName("한 카테고리의 게시 잠금은 다른 카테고리의 관리자 변경을 막지 않는다")
    void locksOnlyRequestedCategory() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                reader.lockActiveForPublication(dailyId);
                var update = executor.submit(() -> transaction.executeWithoutResult(updateStatus -> {
                    jdbc.execute("SET LOCAL lock_timeout = '500ms'");
                    deactivate(wootechId);
                }));

                result(update);
                assertThat(reader.get(wootechId).active()).isFalse();
            });
        }
    }

    @Test
    @DisplayName("일반 조회는 관리자 변경 잠금 때문에 대기하지 않고 마지막 커밋된 값을 읽는다")
    void readsCategoryWhileAdminUpdateIsUncommitted() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                deactivate(dailyId);
                var read = executor.submit(() -> transaction.execute(readStatus -> {
                    jdbc.execute("SET LOCAL lock_timeout = '500ms'");
                    return reader.get(dailyId);
                }));

                assertThat(result(read).active()).isTrue();
            });
            assertThat(reader.get(dailyId).active()).isFalse();
        }
    }

    @Test
    @DisplayName("관리자가 먼저 비활성화 중이면 게시 검증은 기다린 뒤 비활성 예외를 발생시킨다")
    void rejectsPublicationAfterWaitingForAdminDeactivation() throws Exception {
        var started = new CountDownLatch(1);
        var backendPid = new AtomicInteger();
        var publication = new AtomicReference<Future<CategoryReader.Category>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                deactivate(dailyId);
                publication.set(executor.submit(() -> transaction.execute(publicationStatus -> {
                    jdbc.execute("SET LOCAL lock_timeout = '10s'");
                    backendPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    started.countDown();
                    return reader.lockActiveForPublication(dailyId);
                })));
                await(started);
                awaitBlocked(backendPid.get());
            });

            assertThatThrownBy(() -> publication.get().get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(CategoryInactiveException.class);
        }
    }

    @Test
    @DisplayName("이미 엔티티를 읽은 트랜잭션도 게시 시에는 DB의 최신 활성 상태를 검증한다")
    void checksLatestStateInsteadOfPreviouslyLoadedEntity() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                entityManager.find(Category.class, dailyId);
                assertThat(reader.get(dailyId).active()).isTrue();
                result(executor.submit(() -> deactivate(dailyId)));

                reader.lockActiveForPublication(dailyId);
            })).isInstanceOf(CategoryInactiveException.class);
        }
    }

    private long categoryId(String name) {
        return jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, name);
    }

    private CategorySnapshot deactivate(long categoryId) {
        return categoryUpdateService.update(categoryId, null, null, false);
    }

    private <T> T result(Future<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("동시 요청 처리 실패", exception);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 중단", exception);
        }
    }

    private void awaitBlocked(int backendPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbc.queryForObject(
                    "SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, backendPid
            );
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("동시성 테스트 중단", exception);
            }
        }
        throw new IllegalStateException("게시 요청이 관리자 변경 잠금을 기다리지 않았습니다.");
    }
}
