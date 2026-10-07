package com.harudle.generation.adapter.out.db;

import com.harudle.generation.diary.service.port.ImageBackupExecutionLock;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/** 같은 JDBC 연결에서 실행 전체에 걸쳐 세션 advisory lock을 보유한다. */
public final class PostgresImageBackupExecutionLock implements ImageBackupExecutionLock {
    private static final long LOCK_KEY = 0x48415255444c4532L;
    private final DataSource dataSource;

    public PostgresImageBackupExecutionLock(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public boolean executeIfAvailable(Runnable operation) {
        Objects.requireNonNull(operation);
        try (Connection connection = dataSource.getConnection()) {
            try {
                if (!executeLockStatement(connection, "SELECT pg_try_advisory_lock(?)")) {
                    return false;
                }
            } catch (SQLException exception) {
                abort(connection, exception); // 잠금 응답이 유실됐어도 세션 잠금을 풀로 돌려보내지 않는다.
                throw exception;
            }
            Throwable operationFailure = null;
            try {
                operation.run();
                return true;
            } catch (RuntimeException | Error exception) {
                operationFailure = exception;
                throw exception;
            } finally {
                try {
                    if (!executeLockStatement(connection, "SELECT pg_advisory_unlock(?)")) {
                        throw new SQLException("백업 실행 잠금 해제를 확인하지 못했습니다.");
                    }
                } catch (SQLException exception) {
                    abort(connection, exception);
                    if (operationFailure != null) {
                        operationFailure.addSuppressed(exception);
                    } else {
                        throw exception;
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("R2 백업 실행 잠금을 확인하지 못했습니다.", exception);
        }
    }

    private boolean executeLockStatement(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(10);
            statement.setLong(1, LOCK_KEY);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("백업 실행 잠금 응답이 없습니다.");
                }
                return result.getBoolean(1);
            }
        }
    }

    private void abort(Connection connection, SQLException failure) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }
}
