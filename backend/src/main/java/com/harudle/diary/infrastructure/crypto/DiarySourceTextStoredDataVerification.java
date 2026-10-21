package com.harudle.diary.infrastructure.crypto;

import com.harudle.diary.domain.DiarySourceTextCipher;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Authenticates every stored v1 source in a read-only snapshot, returning counts without user content. */
public final class DiarySourceTextStoredDataVerification {

    private static final int BATCH_SIZE = 100;
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final DiarySourceTextCipher cipher;

    public DiarySourceTextStoredDataVerification(DiarySourceTextCipher cipher) {
        require(cipher != null);
        this.cipher = cipher;
    }

    /** Uses a dedicated auto-commit connection; healthy connections remain open with their settings restored. */
    public Result verify(Connection connection) {
        try {
            require(connection != null && !connection.isClosed() && connection.getAutoCommit());
            boolean readOnly = connection.isReadOnly();
            int isolation = connection.getTransactionIsolation();
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            requireUnfilteredTable(connection);
            requireSupportedVersions(connection);
            Result result = verifyBatches(connection);
            connection.rollback();
            connection.setAutoCommit(true);
            connection.setTransactionIsolation(isolation);
            connection.setReadOnly(readOnly);
            return result;
        } catch (SQLException | RuntimeException exception) {
            abort(connection);
            throw new DiarySourceTextCryptoException();
        }
    }

    private void requireUnfilteredTable(Connection connection) throws SQLException {
        try (PreparedStatement policy = prepare(connection, "SET LOCAL row_security = off");
                PreparedStatement lock = prepare(connection, "LOCK TABLE public.diaries IN ACCESS SHARE MODE")) {
            policy.execute();
            lock.execute();
        }
        try (PreparedStatement statement = prepare(connection, """
                SELECT c.relkind, c.relrowsecurity, c.relforcerowsecurity,
                       EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i
                               WHERE i.inhrelid = c.oid OR i.inhparent = c.oid) AS inherited,
                       EXISTS (SELECT 1 FROM pg_catalog.pg_constraint p
                               JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attname = 'id'
                               WHERE p.conrelid = c.oid AND p.contype = 'p' AND p.convalidated
                                 AND p.conkey = ARRAY[a.attnum] AND a.attnotnull AND NOT a.attisdropped
                                 AND a.atttypid = 'pg_catalog.uuid'::regtype) AS unique_cursor
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = 'diaries'
                """); ResultSet row = statement.executeQuery()) {
            require(row.next() && "r".equals(row.getString("relkind"))
                    && !row.getBoolean("relrowsecurity") && !row.getBoolean("relforcerowsecurity")
                    && !row.getBoolean("inherited") && row.getBoolean("unique_cursor"));
        }
    }

    private void requireSupportedVersions(Connection connection) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                SELECT EXISTS (SELECT 1 FROM public.diaries
                WHERE source_text_version IS NULL OR source_text_version NOT IN (0, 1))
                """); ResultSet row = statement.executeQuery()) {
            require(row.next() && !row.getBoolean(1));
        }
    }

    private Result verifyBatches(Connection connection) throws SQLException {
        long checked = 0;
        long failed = 0;
        UUID cursor = null;
        Page page;
        do {
            page = verifyPage(connection, cursor);
            checked += page.checked();
            failed += page.failed();
            cursor = page.cursor();
        } while (page.checked() == BATCH_SIZE);
        return new Result(checked, failed);
    }

    private Page verifyPage(Connection connection, UUID cursor) throws SQLException {
        String sql = "SELECT id, user_id, source_text FROM public.diaries WHERE source_text_version = 1"
                + (cursor == null ? "" : " AND id > ?") + " ORDER BY id LIMIT " + BATCH_SIZE;
        try (PreparedStatement statement = prepare(connection, sql)) {
            if (cursor != null) {
                statement.setObject(1, cursor);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return verifyRows(rows);
            }
        }
    }

    private Page verifyRows(ResultSet rows) throws SQLException {
        long checked = 0;
        long failed = 0;
        UUID last = null;
        while (rows.next()) {
            last = rows.getObject("id", UUID.class);
            checked++;
            if (!authenticates(last, rows.getObject("user_id", UUID.class), rows.getString("source_text"))) {
                failed++;
            }
        }
        return new Page(checked, failed, last);
    }

    private boolean authenticates(UUID diaryId, UUID userId, String ciphertext) {
        try {
            cipher.decrypt(diaryId, userId, ciphertext, DiarySourceTextCipher.CURRENT_VERSION);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            return statement;
        } catch (SQLException exception) {
            statement.close();
            throw exception;
        }
    }

    private void abort(Connection connection) {
        if (connection != null) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException ignored) {
                // Never return an uncertain verification transaction to a pool.
            }
        }
    }

    private void require(boolean condition) {
        if (!condition) {
            throw new DiarySourceTextCryptoException();
        }
    }

    public record Result(long checked, long failed) {
    }

    private record Page(long checked, long failed, UUID cursor) {
    }
}
