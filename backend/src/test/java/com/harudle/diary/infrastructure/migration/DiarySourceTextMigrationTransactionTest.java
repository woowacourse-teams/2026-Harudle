package com.harudle.diary.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DiarySourceTextMigrationTransactionTest {

    private static final String FAILURE_MESSAGE = "일기 원문 전환 작업에 실패했습니다.";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?)";
    private static final String LOCK_TIMEOUT_SQL = "SET LOCAL lock_timeout = '2s'";

    private final Connection connection = mock(Connection.class);
    private final PreparedStatement firstStatement = mock(PreparedStatement.class);
    private final AtomicBoolean autoCommit = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<String> preparedSql = new ArrayList<>();
    private final DiarySourceTextMigration migration = new DiarySourceTextMigration(connection,
            mock(DiarySourceTextCipher.class), new RequestFingerprintGenerator());

    @BeforeEach
    void setUp() throws Exception {
        when(connection.isClosed()).thenAnswer(invocation -> closed.get());
        when(connection.getAutoCommit()).thenAnswer(invocation -> autoCommit.get());
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        doAnswer(invocation -> {
            autoCommit.set(invocation.getArgument(0));
            return null;
        }).when(connection).setAutoCommit(any(Boolean.class));
        doAnswer(invocation -> {
            closed.set(true);
            return null;
        }).when(connection).abort(any());
        doAnswer(invocation -> {
            closed.set(true);
            return null;
        }).when(connection).close();
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            preparedSql.add(sql);
            return LOCK_TIMEOUT_SQL.equals(sql) ? firstStatement : healthyStatement(sql);
        });
    }

    @Test
    @DisplayName("Error는 같은 인스턴스로 전파하고 롤백 완료 뒤에만 자동 커밋을 복원한다")
    void rollsBackErrorBeforeRestoringAutoCommit() throws Exception {
        AssertionError failure = interruptTransaction();

        assertThatThrownBy(migration::status).isSameAs(failure);

        var order = inOrder(connection, firstStatement);
        order.verify(connection).setAutoCommit(false);
        order.verify(firstStatement).execute();
        order.verify(connection).rollback();
        order.verify(connection).setAutoCommit(true);
        verify(connection, never()).commit();
        verify(connection, never()).abort(any());
        verify(connection, never()).close();
        assertThat(autoCommit).isTrue();
    }

    @ParameterizedTest
    @MethodSource("cleanupFailures")
    @DisplayName("롤백 실패는 원래 Error를 보존하고 자동 커밋과 unlock 없이 연결을 폐기한다")
    void discardsUncertainRollbackWithoutRestorationOrUnlock(Throwable cleanupFailure) throws Exception {
        AssertionError failure = interruptTransaction();
        doThrow(cleanupFailure).when(connection).rollback();

        assertThatThrownBy(() -> migration.migrate(1, 1)).isSameAs(failure);

        verify(connection).rollback();
        verify(connection).abort(any());
        verify(connection, never()).setAutoCommit(true);
        verify(connection, never()).commit();
        assertThat(preparedSql).doesNotContain(UNLOCK_SQL);
        assertSanitizedSuppressed(failure);
        assertUnusable();
    }

    @ParameterizedTest
    @MethodSource("cleanupFailures")
    @DisplayName("커밋 응답이 불명확하면 롤백이나 자동 커밋을 시도하지 않고 연결을 폐기한다")
    void discardsUncertainCommitWithoutRollbackOrRestoration(Throwable commitFailure) throws Exception {
        doThrow(commitFailure).when(connection).commit();

        var thrown = assertThatThrownBy(() -> migration.migrate(1, 1));
        if (commitFailure instanceof Error) {
            thrown.isSameAs(commitFailure);
        } else {
            thrown.isExactlyInstanceOf(DiarySourceTextMigrationException.class)
                    .hasMessage(FAILURE_MESSAGE).hasNoCause();
        }
        verify(connection).commit();
        verify(connection).abort(any());
        verify(connection, never()).rollback();
        verify(connection, never()).setAutoCommit(true);
        assertThat(preparedSql).doesNotContain(UNLOCK_SQL);
        assertUnusable();
    }

    @Test
    @DisplayName("자동 커밋 복원 실패가 원래 Error를 덮지 않고 연결을 재사용하지 않는다")
    void preservesOriginalErrorWhenAutoCommitRestorationFails() throws Exception {
        AssertionError failure = interruptTransaction();
        doThrow(new SQLException("private-auto-commit-cleanup")).when(connection).setAutoCommit(true);

        assertThatThrownBy(migration::status).isSameAs(failure);

        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).abort(any());
        assertSanitizedSuppressed(failure);
        assertUnusable();
    }

    @Test
    @DisplayName("롤백 뒤 연결 상태 확인 Error도 자동 커밋을 건너뛰고 원래 Error를 보존한다")
    void preservesOriginalErrorWhenConnectionInspectionFails() throws Exception {
        AssertionError failure = interruptTransaction();
        when(connection.isClosed()).thenReturn(false).thenThrow(new AssertionError("private-state-cleanup"));

        assertThatThrownBy(migration::status).isSameAs(failure);

        verify(connection).rollback();
        verify(connection, never()).setAutoCommit(true);
        verify(connection).abort(any());
        assertSanitizedSuppressed(failure);
    }

    @ParameterizedTest
    @MethodSource("cleanupFailures")
    @DisplayName("advisory unlock 실패도 원래 Error를 보존하고 민감한 정리 원인 대신 고정 진단만 붙인다")
    void preservesOriginalErrorWhenUnlockFails(Throwable cleanupFailure) throws Exception {
        AssertionError failure = interruptTransaction();
        when(connection.prepareStatement(UNLOCK_SQL)).thenThrow(cleanupFailure);

        assertThatThrownBy(() -> migration.migrate(1, 1)).isSameAs(failure);

        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).abort(any());
        assertSanitizedSuppressed(failure);
        assertUnusable();
    }

    @Test
    @DisplayName("abort도 실패하면 close로 폐기하고 정리 실패가 원래 Error를 덮지 않는다")
    void closesConnectionWhenAbortFailsWithoutReplacingOriginalError() throws Exception {
        AssertionError failure = interruptTransaction();
        doThrow(new SQLException("private-rollback-cleanup")).when(connection).rollback();
        doThrow(new AssertionError("private-abort-cleanup")).when(connection).abort(any());
        doThrow(new SQLException("private-close-cleanup")).when(connection).close();

        assertThatThrownBy(() -> migration.migrate(1, 1)).isSameAs(failure);

        verify(connection).abort(any());
        verify(connection).close();
        verify(connection, never()).setAutoCommit(true);
        assertSanitizedSuppressed(failure);
        assertUnusable();
    }

    @ParameterizedTest
    @ValueSource(ints = {Connection.TRANSACTION_NONE, Connection.TRANSACTION_READ_UNCOMMITTED,
            Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE, 42})
    @DisplayName("READ_COMMITTED 이외의 격리 수준은 잠금과 트랜잭션 시작 전에 거절하고 연결 설정을 보존한다")
    void rejectsUnsupportedIsolationBeforeAnyDatabaseWork(int isolation) throws Exception {
        when(connection.getTransactionIsolation()).thenReturn(isolation);

        assertThatThrownBy(() -> migration.migrate(1, 1))
                .isExactlyInstanceOf(DiarySourceTextMigrationException.class)
                .hasMessage(FAILURE_MESSAGE).hasNoCause();

        assertRejectedBeforeDatabaseWork();
        assertThat(connection.getTransactionIsolation()).isEqualTo(isolation);
        assertThat(autoCommit).isTrue();
    }

    @Test
    @DisplayName("격리 수준 조회 실패는 원인을 노출하지 않고 잠금과 트랜잭션 시작 전에 거절한다")
    void rejectsIsolationInspectionFailureBeforeAnyDatabaseWork() throws Exception {
        when(connection.getTransactionIsolation()).thenThrow(new SQLException("private-isolation-diagnostic"));

        assertThatThrownBy(() -> migration.migrate(1, 1))
                .isExactlyInstanceOf(DiarySourceTextMigrationException.class)
                .hasMessage(FAILURE_MESSAGE).hasNoCause();

        assertRejectedBeforeDatabaseWork();
        assertThat(autoCommit).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "verify", "ready"})
    @DisplayName("조회 명령은 격리 수준을 제한하거나 변경하지 않고 기존 트랜잭션 경로로 진행한다")
    void allowsReadOnlyOperationsWithoutInspectingIsolation(String command) throws Exception {
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        AssertionError failure = interruptTransaction();

        assertThatThrownBy(() -> operation(command).run()).isSameAs(failure);

        verify(connection, never()).getTransactionIsolation();
        verify(connection, never()).setTransactionIsolation(anyInt());
        verify(connection).rollback();
        assertThat(autoCommit).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "migrate"})
    @DisplayName("호출자가 시작한 트랜잭션은 거절만 하고 커밋과 롤백 및 연결 폐기를 하지 않는다")
    void leavesCallerOwnedTransactionUntouched(String command) throws Exception {
        autoCommit.set(false);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);

        assertThatThrownBy(() -> operation(command).run())
                .isExactlyInstanceOf(DiarySourceTextMigrationException.class);

        assertRejectedBeforeDatabaseWork();
        verify(connection, never()).getTransactionIsolation();
        assertThat(autoCommit).isFalse();
    }

    private void assertRejectedBeforeDatabaseWork() throws SQLException {
        verify(connection, never()).setAutoCommit(any(Boolean.class));
        verify(connection, never()).setTransactionIsolation(anyInt());
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
        verify(connection, never()).abort(any());
        verify(connection, never()).close();
        assertThat(preparedSql).isEmpty();
        assertThat(closed).isFalse();
    }

    private Runnable operation(String command) {
        return switch (command) {
            case "status" -> migration::status;
            case "migrate" -> () -> migration.migrate(1, 1);
            case "verify" -> () -> migration.verify(1);
            case "ready" -> migration::requireReady;
            default -> throw new IllegalArgumentException("Unsupported test command.");
        };
    }

    private AssertionError interruptTransaction() throws SQLException {
        AssertionError failure = new AssertionError("original transaction interruption");
        when(firstStatement.execute()).thenThrow(failure);
        return failure;
    }

    private void assertUnusable() {
        int statementsBeforeRetry = preparedSql.size();
        assertThatThrownBy(migration::status).isExactlyInstanceOf(DiarySourceTextMigrationException.class)
                .hasMessage(FAILURE_MESSAGE).hasNoCause();
        assertThat(preparedSql).hasSize(statementsBeforeRetry);
    }

    private static void assertSanitizedSuppressed(Throwable failure) {
        assertThat(failure.getSuppressed()).isNotEmpty().allSatisfy(suppressed ->
                assertThat(suppressed).isExactlyInstanceOf(DiarySourceTextMigrationException.class)
                        .hasMessage(FAILURE_MESSAGE).hasNoCause());
    }

    private PreparedStatement healthyStatement(String sql) throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet row = mock(ResultSet.class);
        when(statement.executeQuery()).thenReturn(row);
        when(row.next()).thenReturn(!sql.contains("pg_get_expr")
                && !sql.startsWith("SELECT id FROM public.diaries WHERE source_text_version = 0"));
        if (sql.contains("pg_try_advisory_lock") || sql.contains("pg_advisory_unlock")
                || sql.contains("SELECT EXISTS")) {
            when(row.getBoolean(1)).thenReturn(true);
        }
        if (sql.contains("SELECT c.relkind")) {
            when(row.getString("relkind")).thenReturn("r");
        }
        if (sql.contains("formatted_type")) {
            AtomicReference<String> column = new AtomicReference<>();
            doAnswer(invocation -> {
                column.set(invocation.getArgument(1));
                return null;
            }).when(statement).setString(eq(2), anyString());
            when(row.getBoolean("attnotnull")).thenReturn(true);
            when(row.getBoolean("plain_column")).thenReturn(true);
            when(row.getBoolean("built_in")).thenReturn(true);
            when(row.getString("formatted_type")).thenAnswer(invocation -> switch (column.get()) {
                case "diary_date" -> "date";
                case "source_text" -> "text";
                case "source_text_version" -> "integer";
                default -> "uuid";
            });
        }
        return statement;
    }

    private static Stream<Throwable> cleanupFailures() {
        return Stream.of(new SQLException("private-driver-diagnostic"),
                new IllegalStateException("private-runtime-diagnostic"),
                new AssertionError("private-error-diagnostic"));
    }
}
