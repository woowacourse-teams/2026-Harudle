package com.harudle.diary.infrastructure.crypto;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Creates first key evidence only for a known schema containing no encrypted diary rows.
 * All writers must already be stopped and drained; the short database lock cannot enforce that after commit.
 * Uses a dedicated caller-owned auto-commit connection and never publishes evidence before commit is confirmed.
 * An uncertain commit, rollback or connection restoration aborts the connection; its owner must discard it.
 */
public final class DiarySourceTextKeyInitialization {

    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int LAST_KNOWN_MIGRATION = 28;

    private final DiarySourceTextKeyVerification verification;

    public DiarySourceTextKeyInitialization(DiarySourceTextKeyVerification verification) {
        require(verification != null);
        this.verification = verification;
    }

    public void initialize(Connection connection, Path file, String environment) {
        FileChannel lockChannel = null;
        FileLock lock = null;
        Path staging = null;
        try {
            require(connection != null && file != null && !connection.isClosed() && connection.getAutoCommit());
            Path target = file.toAbsolutePath().normalize();
            lockChannel = openLock(target);
            lock = lockChannel.tryLock();
            require(lock != null && !Files.exists(target, LinkOption.NOFOLLOW_LINKS));
            staging = target.resolveSibling(".diary-key-initialization-" + UUID.randomUUID() + ".json");
            createStagedEvidence(connection, staging, environment);
            Files.createLink(target, staging);
            DiarySourceTextKeyVerification.forceParentDirectory(target);
        } catch (SQLException | IOException | RuntimeException exception) {
            throw new DiarySourceTextCryptoException();
        } finally {
            deleteStaging(staging);
            releaseLock(lock, lockChannel);
        }
    }

    private FileChannel openLock(Path target) throws IOException {
        return FileChannel.open(target.resolveSibling(target.getFileName() + ".lock"),
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    private void createStagedEvidence(Connection connection, Path staging, String environment) throws SQLException {
        int isolation = connection.getTransactionIsolation();
        boolean resolved = false;
        boolean commitAttempted = false;
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            requireEligibleDatabase(connection);
            verification.createInitialEvidence(staging, environment);
            commitAttempted = true;
            connection.commit();
            resolved = true;
        } catch (SQLException | RuntimeException exception) {
            resolved = rollback(connection) && !commitAttempted;
            if (!resolved) {
                abort(connection);
            }
            throw new DiarySourceTextCryptoException();
        } finally {
            if (resolved) {
                restoreConnection(connection, isolation);
            }
        }
    }

    private void requireEligibleDatabase(Connection connection) throws SQLException {
        execute(connection, "SET LOCAL lock_timeout = '2s'");
        execute(connection, "SET LOCAL statement_timeout = '10s'");
        execute(connection, "SET LOCAL row_security = off");
        execute(connection, "LOCK TABLE public.diaries IN SHARE MODE");
        requireOrdinaryTable(connection, "diaries");
        requireOrdinaryTable(connection, "flyway_schema_history");
        Map<String, Column> columns = columns(connection);
        requireColumn(columns, "id", "uuid");
        requireColumn(columns, "user_id", "uuid");
        requireColumn(columns, "diary_date", "date");
        History history = history(connection);
        requireEligibleVersionShape(connection, columns, history);
    }

    private void requireEligibleVersionShape(
            Connection connection,
            Map<String, Column> columns,
            History history
    ) throws SQLException {
        if (!columns.containsKey("source_text_version")) {
            require(!history.version28());
            requireColumn(columns, "source_text", "character varying(300)");
            return;
        }
        require(history.version28());
        requireColumn(columns, "source_text", "text");
        requireColumn(columns, "source_text_version", "integer");
        try (PreparedStatement statement = prepare(connection, """
                SELECT EXISTS (SELECT 1 FROM public.diaries
                               WHERE source_text_version IS NULL OR source_text_version <> 0)
                """)) {
            try (ResultSet row = statement.executeQuery()) {
                require(row.next() && !row.getBoolean(1));
            }
        }
    }

    private void requireOrdinaryTable(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                SELECT c.relkind, c.relrowsecurity, c.relforcerowsecurity,
                       EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i
                               WHERE i.inhrelid = c.oid OR i.inhparent = c.oid) AS inherited
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relname = ?
                """)) {
            statement.setString(1, table);
            try (ResultSet row = statement.executeQuery()) {
                require(row.next() && "r".equals(row.getString("relkind"))
                        && !row.getBoolean("relrowsecurity") && !row.getBoolean("relforcerowsecurity")
                        && !row.getBoolean("inherited"));
            }
        }
    }

    private Map<String, Column> columns(Connection connection) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod) AS formatted_type,
                       a.attnotnull, n.nspname = 'pg_catalog' AS built_in,
                       a.attgenerated = '' AND a.attidentity = '' AS plain_column
                FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                WHERE a.attrelid = 'public.diaries'::regclass AND a.attnum > 0 AND NOT a.attisdropped
                """)) {
            return readColumns(statement);
        }
    }

    private Map<String, Column> readColumns(PreparedStatement statement) throws SQLException {
        Map<String, Column> columns = new HashMap<>();
        try (ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                columns.put(row.getString("attname"), new Column(row.getString("formatted_type"),
                        row.getBoolean("attnotnull"), row.getBoolean("built_in"), row.getBoolean("plain_column")));
            }
        }
        return columns;
    }

    private void requireColumn(Map<String, Column> columns, String name, String type) {
        Column column = columns.get(name);
        require(column != null && column.required() && column.builtIn() && column.plain()
                && type.equals(column.type()));
    }

    private History history(Connection connection) throws SQLException {
        Set<Integer> versions = new HashSet<>();
        try (PreparedStatement statement = prepare(connection,
                "SELECT version, type, success FROM public.flyway_schema_history LIMIT "
                        + (LAST_KNOWN_MIGRATION + 1))) {
            readVersions(statement, versions);
        }
        require(versions.contains(1));
        return new History(versions.contains(LAST_KNOWN_MIGRATION));
    }

    private void readVersions(PreparedStatement statement, Set<Integer> versions) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                String version = row.getString("version");
                require(version != null && version.matches("[1-9][0-9]?"));
                int number = Integer.parseInt(version);
                require(number <= LAST_KNOWN_MIGRATION && versions.add(number)
                        && row.getBoolean("success") && "SQL".equals(row.getString("type")));
            }
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

    private void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql)) {
            statement.execute();
        }
    }

    private boolean rollback(Connection connection) {
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException exception) {
            abort(connection);
            return false;
        }
    }

    private void restoreConnection(Connection connection, int isolation) {
        try {
            connection.setAutoCommit(true);
            connection.setTransactionIsolation(isolation);
        } catch (SQLException | RuntimeException exception) {
            abort(connection);
            throw new DiarySourceTextCryptoException();
        }
    }

    private void abort(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException ignored) {
            closeUnusableConnection(connection);
            // The caller must discard this dedicated connection after an uncertain transaction outcome.
        }
    }

    private void closeUnusableConnection(Connection connection) {
        try {
            connection.close();
        } catch (SQLException | RuntimeException ignored) {
            // A driver that cannot abort or close must still never be reused by the CLI owner.
        }
    }

    private void deleteStaging(Path staging) {
        if (staging == null) {
            return;
        }
        deleteQuietly(staging);
        deleteQuietly(staging.resolveSibling(staging.getFileName() + ".lock"));
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ignored) {
            // Unpublished staging files are never a verification source; published evidence remains valid.
        }
    }

    private void releaseLock(FileLock lock, FileChannel channel) {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException | RuntimeException ignored) {
            // A completed publication remains valid even when resource cleanup fails.
        } finally {
            closeQuietly(channel);
        }
    }

    private void closeQuietly(FileChannel channel) {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException | RuntimeException ignored) {
            // The one-off CLI process owns the final file lock and releases it on exit.
        }
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new DiarySourceTextCryptoException();
        }
    }

    private record Column(String type, boolean required, boolean builtIn, boolean plain) {
    }

    private record History(boolean version28) {
    }
}
