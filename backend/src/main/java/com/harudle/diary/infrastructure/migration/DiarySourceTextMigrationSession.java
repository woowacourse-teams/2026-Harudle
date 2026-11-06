package com.harudle.diary.infrastructure.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Owns JDBC execution, transaction recovery and the session-level execution lock. */
final class DiarySourceTextMigrationSession {

    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final Connection connection;
    private boolean unusable;

    DiarySourceTextMigrationSession(Connection connection) {
        this.connection = connection;
    }

    int transactionIsolation() throws SQLException {
        return connection.getTransactionIsolation();
    }

    boolean isUnusable() {
        return unusable;
    }

    PreparedStatement prepare(String sql) throws SQLException {
        require(!unusable);
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return statement;
    }

    void execute(String sql) throws SQLException {
        try (PreparedStatement statement = prepare(sql)) {
            statement.execute();
        }
    }

    boolean readBoolean(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            require(row.next());
            return row.getBoolean(1);
        }
    }

    /** Never queue an exclusive schema lock behind serving requests; bound SQL and idle gaps once acquired. */
    void acquireSchemaChangeLock() throws SQLException {
        execute("SET LOCAL statement_timeout = '1s'");
        execute("SET LOCAL idle_in_transaction_session_timeout = '1s'");
        execute("LOCK TABLE public.diaries IN ACCESS EXCLUSIVE MODE NOWAIT");
    }

    <T> T transaction(SqlOperation<T> operation) throws SQLException {
        require(!unusable);
        try {
            connection.setAutoCommit(false);
        } catch (SQLException | RuntimeException | Error exception) {
            abort(exception);
            throw exception;
        }
        boolean commitAttempted = false;
        boolean transactionEnded = false;
        Throwable failure = null;
        try {
            execute("SET LOCAL lock_timeout = '2s'");
            execute("SET LOCAL statement_timeout = '10s'");
            T result = operation.run();
            commitAttempted = true;
            connection.commit();
            transactionEnded = true;
            return result;
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            if (commitAttempted) {
                abort(exception);
            } else {
                transactionEnded = rollbackSafely(exception);
            }
            throw exception;
        } finally {
            // Enabling auto-commit may commit an open transaction; only restore after a confirmed end.
            if (transactionEnded) {
                restoreAutoCommit(failure);
            }
        }
    }

    private boolean rollbackSafely(Throwable failure) {
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException | Error exception) {
            suppressCleanupFailure(failure);
            abort(failure);
            return false;
        }
    }

    private void restoreAutoCommit(Throwable failure) {
        if (unusable) {
            return;
        }
        try {
            require(!connection.isClosed());
            connection.setAutoCommit(true);
        } catch (SQLException | RuntimeException | Error exception) {
            handleCleanupFailure(exception, failure);
        }
    }

    <T> T exclusively(SqlOperation<T> operation) throws SQLException {
        acquireExecutionLock();
        Throwable failure = null;
        try {
            return operation.run();
        } catch (SQLException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            releaseExecutionLock(failure);
        }
    }

    private void acquireExecutionLock() throws SQLException {
        boolean acquired;
        try {
            acquired = advisory("SELECT pg_try_advisory_lock(?)");
        } catch (SQLException | RuntimeException | Error exception) {
            abort(exception);
            throw exception;
        }
        require(acquired);
    }

    private void releaseExecutionLock(Throwable failure) {
        if (unusable) {
            return;
        }
        try {
            require(!connection.isClosed());
            require(advisory("SELECT pg_advisory_unlock(?)"));
        } catch (SQLException | RuntimeException | Error exception) {
            handleCleanupFailure(exception, failure);
        }
    }

    private void handleCleanupFailure(Throwable cleanupFailure, Throwable failure) {
        abort(failure == null ? cleanupFailure : failure);
        if (failure != null) {
            suppressCleanupFailure(failure);
            return;
        }
        if (cleanupFailure instanceof Error error) {
            throw error;
        }
        throw new DiarySourceTextMigrationException();
    }

    private boolean advisory(String sql) throws SQLException {
        try (PreparedStatement statement = prepare(sql)) {
            statement.setLong(1, DiarySourceTextMigration.EXECUTION_LOCK_KEY);
            return readBoolean(statement);
        }
    }

    private void abort(Throwable failure) {
        unusable = true;
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException | Error exception) {
            suppressCleanupFailure(failure);
            closeAfterAbortFailure(failure);
        }
    }

    private void closeAfterAbortFailure(Throwable failure) {
        try {
            connection.close();
        } catch (SQLException | RuntimeException | Error exception) {
            // The instance stays unusable even when the driver cannot confirm physical connection disposal.
            suppressCleanupFailure(failure);
        }
    }

    private static void suppressCleanupFailure(Throwable failure) {
        try {
            failure.addSuppressed(new DiarySourceTextMigrationException());
        } catch (RuntimeException | Error ignored) {
            // Cleanup diagnostics must not replace the original failure, including under memory pressure.
        }
    }

    <T> T safely(SqlOperation<T> operation) {
        try {
            require(!unusable && !connection.isClosed() && connection.getAutoCommit());
            return operation.run();
        } catch (DiarySourceTextMigrationException exception) {
            throw exception;
        } catch (SQLException | RuntimeException exception) {
            throw new DiarySourceTextMigrationException();
        }
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new DiarySourceTextMigrationException();
        }
    }

    @FunctionalInterface
    interface SqlOperation<T> {

        T run() throws SQLException;
    }
}
