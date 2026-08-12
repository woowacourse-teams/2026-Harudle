package com.harudle.generation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.generation.domain.GenerationPrompt;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class GenerationPromptBootstrapPersistenceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final int CONCURRENT_REQUEST_COUNT = 2;
    private static final long CONCURRENCY_READY_TIMEOUT_SECONDS = 5L;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private GenerationPromptBootstrapService bootstrapService;

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createNativeQuery("DELETE FROM generation_prompts").executeUpdate());
    }

    @Test
    @DisplayName("동시 프롬프트 초기화 요청은 하나의 프롬프트만 저장한다")
    void initializePromptAtomicallyUnderConcurrency() throws InterruptedException {
        List<Callable<Optional<GenerationPrompt>>> initializations = IntStream
                .range(0, CONCURRENT_REQUEST_COUNT)
                .mapToObj(index -> (Callable<Optional<GenerationPrompt>>) () -> bootstrapService.createIfEmpty(
                        new GenerationPrompt(
                                "스토리보드 프롬프트",
                                "이미지 스타일 프롬프트",
                                "references/style.png"
                        )
                ))
                .toList();

        List<Optional<GenerationPrompt>> results = executeConcurrently(initializations);

        assertThat(results.stream().flatMap(Optional::stream)).hasSize(1);
        assertThat(countPrompts()).isEqualTo(1);
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
            throw new IllegalStateException("동시 실행 결과를 가져오지 못했습니다.", exception);
        }
    }

    private int countPrompts() {
        return transactionTemplate.execute(status -> {
            Number count = (Number) entityManager
                    .createNativeQuery("SELECT COUNT(*) FROM generation_prompts")
                    .getSingleResult();
            return count.intValue();
        });
    }
}
