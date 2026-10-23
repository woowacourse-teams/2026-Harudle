package com.harudle.generation.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LoadTestGenerationRegistryTest {

    private final UUID generationId = UUID.randomUUID();
    private final LoadTestDiaryGenerationExecutor executor = mock(LoadTestDiaryGenerationExecutor.class);
    private final LoadTestGenerationRegistry registry = new LoadTestGenerationRegistry(executor);
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    @DisplayName("저장이 끝나기 전에 Stub을 연결하고 저장 성공 후에도 유지한다")
    void registerBeforeCommitAndKeepAfterCommit() {
        transaction.executeWithoutResult(status -> {
            registry.register(generationId);
            assertThat(registry.findExecutor(generationId)).contains(executor);
        });

        assertThat(registry.findExecutor(generationId)).contains(executor);
        assertThat(registry.findExecutor(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("작업 저장이 취소되면 새로 등록한 연결도 지운다")
    void removeRegistrationOnRollback() {
        transaction.executeWithoutResult(status -> {
            registry.register(generationId);
            status.setRollbackOnly();
        });

        assertThat(registry.findExecutor(generationId)).isEmpty();
    }

    @Test
    @DisplayName("재요청 저장이 취소되어도 앞서 저장한 작업의 연결은 유지한다")
    void keepExistingRegistrationWhenReplayRollsBack() {
        transaction.executeWithoutResult(status -> registry.register(generationId));
        transaction.executeWithoutResult(status -> {
            registry.register(generationId);
            status.setRollbackOnly();
        });

        assertThat(registry.findExecutor(generationId)).contains(executor);
    }

    @Test
    @DisplayName("작업 처리가 끝나면 해당 작업의 연결만 지운다")
    void removeOnlyFinishedGeneration() {
        UUID otherId = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            registry.register(generationId);
            registry.register(otherId);
        });

        registry.remove(generationId);

        assertThat(registry.findExecutor(generationId)).isEmpty();
        assertThat(registry.findExecutor(otherId)).contains(executor);
    }

    @Test
    @DisplayName("저장 트랜잭션 없이 연결을 등록하지 않는다")
    void rejectRegistrationWithoutTransaction() {
        assertThatThrownBy(() -> registry.register(generationId)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.findExecutor(generationId)).isEmpty();
    }
}
