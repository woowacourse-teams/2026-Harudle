package com.harudle.generation.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.DiaryGenerationCompletionService;
import com.harudle.generation.diary.service.GenerationLifecycleMetrics;
import com.harudle.generation.diary.service.event.DiaryGenerationPendingExpiredEvent;
import com.harudle.generation.usage.service.GenerationUsageService;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

class LoadTestGenerationPendingExpiryTest {

    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private static final Duration PENDING_TIMEOUT = Duration.ofSeconds(120);
    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 10, 21);

    private final DiaryGenerationRepository generations = mock(DiaryGenerationRepository.class);
    private final DiaryRepository diaries = mock(DiaryRepository.class);
    private final GenerationUsageService usage = mock(GenerationUsageService.class);
    private final LoadTestDiaryGenerationExecutor executor = mock(LoadTestDiaryGenerationExecutor.class);
    private final LoadTestGenerationRegistry registry = new LoadTestGenerationRegistry(executor);
    private final Diary diary = Diary.create(UUID.randomUUID(), USAGE_DATE, "대기 만료 테스트");
    private final DiaryGeneration generation = DiaryGeneration.pending(
            diary.getId(), 1L, UUID.randomUUID(), "a".repeat(64), USAGE_DATE
    );

    private Connection connection;
    private TransactionTemplate transaction;
    private ApplicationContextRunner contextRunner;

    @BeforeEach
    void setUp() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        ReflectionTestUtils.setField(generation, "createdAt", NOW.minus(PENDING_TIMEOUT));
        when(generations.findByIdForUpdate(generation.getId())).thenReturn(Optional.of(generation));
        when(diaries.findByIdIncludingDeletedForUpdate(diary.getId())).thenReturn(Optional.of(diary));

        contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(TransactionConfiguration.class)
                .withBean("transactionManager", PlatformTransactionManager.class, () -> transactionManager)
                .withBean("serviceClock", Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                .withBean(DiaryGenerationRepository.class, () -> generations)
                .withBean(DiaryRepository.class, () -> diaries)
                .withBean(GenerationUsageService.class, () -> usage)
                .withBean(GenerationLifecycleMetrics.class, () -> mock(GenerationLifecycleMetrics.class))
                .withBean(DiaryGenerationCompletionService.class);
    }

    @Test
    @DisplayName("만료 저장이 커밋된 뒤 해당 테스트 ID만 지우고 다른 ID는 유지한다")
    void removeExpiredIdOnlyAfterCommit() {
        UUID otherId = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            registry.register(generation.getId());
            registry.register(otherId);
        });

        contextRunner.withBean(LoadTestGenerationRegistry.class, () -> registry).run(context -> {
            var completion = context.getBean(DiaryGenerationCompletionService.class);
            transaction.executeWithoutResult(status -> {
                assertThat(completion.expirePendingIfStale(generation.getId(), NOW, PENDING_TIMEOUT)).isTrue();
                assertThat(registry.findExecutor(generation.getId())).contains(executor);
            });

            assertThat(registry.findExecutor(generation.getId())).isEmpty();
            assertThat(registry.findExecutor(otherId)).contains(executor);
            assertThat(completion.expirePendingIfStale(generation.getId(), NOW, PENDING_TIMEOUT)).isFalse();
            verify(usage).restoreUsage(diary.getUserId(), USAGE_DATE);
        });
    }

    @Test
    @DisplayName("만료 저장을 취소하면 기존 테스트 ID를 유지한다")
    void keepIdWhenExpiryRollsBack() {
        registerTestId();

        contextRunner.withBean(LoadTestGenerationRegistry.class, () -> registry).run(context -> {
            var completion = context.getBean(DiaryGenerationCompletionService.class);
            transaction.executeWithoutResult(status -> {
                assertThat(completion.expirePendingIfStale(generation.getId(), NOW, PENDING_TIMEOUT)).isTrue();
                status.setRollbackOnly();
            });

            assertThat(registry.findExecutor(generation.getId())).contains(executor);
        });
    }

    @Test
    @DisplayName("DB 커밋이 실패하면 기존 테스트 ID를 유지한다")
    void keepIdWhenCommitFails() throws SQLException {
        registerTestId();
        doThrow(new SQLException("테스트 커밋 실패")).when(connection).commit();

        contextRunner.withBean(LoadTestGenerationRegistry.class, () -> registry).run(context -> {
            var completion = context.getBean(DiaryGenerationCompletionService.class);

            assertThatThrownBy(() -> completion.expirePendingIfStale(generation.getId(), NOW, PENDING_TIMEOUT))
                    .isInstanceOf(TransactionSystemException.class);

            assertThat(registry.findExecutor(generation.getId())).contains(executor);
        });
    }

    @Test
    @DisplayName("사용량 복구가 실패해 만료 저장이 취소되면 테스트 ID를 유지한다")
    void keepIdWhenUsageRestoreFails() {
        registerTestId();
        doThrow(new IllegalStateException("테스트 사용량 복구 실패"))
                .when(usage).restoreUsage(diary.getUserId(), USAGE_DATE);

        contextRunner.withBean(LoadTestGenerationRegistry.class, () -> registry).run(context -> {
            var completion = context.getBean(DiaryGenerationCompletionService.class);

            assertThatThrownBy(() -> completion.expirePendingIfStale(generation.getId(), NOW, PENDING_TIMEOUT))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(registry.findExecutor(generation.getId())).contains(executor);
        });
    }

    @Test
    @DisplayName("만료 저장 트랜잭션 없이 받은 알림으로는 테스트 ID를 지우지 않는다")
    void ignoreEventOutsideTransaction() {
        registerTestId();

        contextRunner.withBean(LoadTestGenerationRegistry.class, () -> registry).run(context -> {
            context.publishEvent(new DiaryGenerationPendingExpiredEvent(generation.getId()));

            assertThat(registry.findExecutor(generation.getId())).contains(executor);
        });
    }

    @Test
    @DisplayName("부하 테스트 코드가 등록되지 않은 환경에서도 일반 대기 작업을 만료한다")
    void expireWithoutLoadTestRegistry() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(LoadTestGenerationRegistry.class);
            var completion = context.getBean(DiaryGenerationCompletionService.class);

            assertThat(completion.expirePendingIfStale(generation.getId(), NOW, PENDING_TIMEOUT)).isTrue();
            verify(usage).restoreUsage(diary.getUserId(), USAGE_DATE);
        });
    }

    private void registerTestId() {
        transaction.executeWithoutResult(status -> registry.register(generation.getId()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}
