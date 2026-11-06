package com.harudle.diary.infrastructure.migration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Checks the supported schema under table locks and applies the explicit v1-only constraints. */
final class DiarySourceTextMigrationSchema {

    private static final String ENCRYPTED_ONLY_CONSTRAINT = "ck_diaries_source_text_encrypted_only";

    private final DiarySourceTextMigrationSession jdbc;

    DiarySourceTextMigrationSchema(DiarySourceTextMigrationSession jdbc) {
        this.jdbc = jdbc;
    }

    boolean isFinalized() throws SQLException {
        Constraint constraint = readConstraint();
        try (PreparedStatement statement = jdbc.prepare("""
                SELECT a.attnotnull, a.atthasdef FROM pg_attribute a
                WHERE a.attrelid = 'public.diaries'::regclass AND a.attname = 'source_text_version' AND NOT a.attisdropped
                """)) {
            try (ResultSet row = statement.executeQuery()) {
                require(row.next());
                return constraint.exists() && constraint.exact() && constraint.validated()
                        && row.getBoolean("attnotnull") && !row.getBoolean("atthasdef");
            }
        }
    }

    private Constraint readConstraint() throws SQLException {
        try (PreparedStatement statement = jdbc.prepare("""
                SELECT c.contype, c.convalidated, c.connoinherit,
                       pg_get_expr(c.conbin, c.conrelid) AS expression,
                       array_length(c.conkey, 1) AS columns,
                       a.attnum = ANY(c.conkey) AS correct_column
                FROM pg_constraint c JOIN pg_attribute a
                  ON a.attrelid = c.conrelid AND a.attname = 'source_text_version' AND NOT a.attisdropped
                WHERE c.conrelid = 'public.diaries'::regclass AND c.conname = ?
                """)) {
            statement.setString(1, ENCRYPTED_ONLY_CONSTRAINT);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? readConstraint(row) : new Constraint(false, false, false);
            }
        }
    }

    private Constraint readConstraint(ResultSet row) throws SQLException {
        String expression = row.getString("expression");
        boolean exact = "c".equals(row.getString("contype")) && !row.getBoolean("connoinherit")
                && row.getInt("columns") == 1 && row.getBoolean("correct_column")
                && expression != null && "source_text_version=1".equals(expression.replaceAll("[\\s()]", ""));
        return new Constraint(true, exact, row.getBoolean("convalidated"));
    }

    /** Install the new-write guard without scanning existing rows under the exclusive lock. */
    void prepareEncryptedOnlyConstraint() throws SQLException {
        Constraint constraint = readConstraint();
        if (constraint.exists()) {
            require(constraint.exact());
            return;
        }
        jdbc.acquireSchemaChangeLock();
        jdbc.execute("ALTER TABLE public.diaries ADD CONSTRAINT " + ENCRYPTED_ONLY_CONSTRAINT
                + " CHECK (source_text_version = 1) NOT VALID");
    }

    /** Validation scans existing rows while normal reads and writes remain possible. */
    void validateEncryptedOnlyConstraint() throws SQLException {
        Constraint constraint = readConstraint();
        require(constraint.exists() && constraint.exact());
        if (!constraint.validated()) {
            jdbc.execute("ALTER TABLE public.diaries VALIDATE CONSTRAINT " + ENCRYPTED_ONLY_CONSTRAINT);
        }
    }

    /** A validated exact CHECK and the checked NOT NULL column prove v1 without another row scan. */
    void removeVersionDefault() throws SQLException {
        Constraint constraint = readConstraint();
        require(constraint.exists() && constraint.exact() && constraint.validated());
        if (isFinalized()) {
            return;
        }
        jdbc.acquireSchemaChangeLock();
        jdbc.execute("ALTER TABLE public.diaries ALTER COLUMN source_text_version DROP DEFAULT");
    }

    void lockAndRequire() {
        lockAndRequire("ACCESS SHARE");
    }

    void lockForValidation() {
        lockAndRequire("SHARE UPDATE EXCLUSIVE");
    }

    /** Keeps schema identity and row visibility stable until this transaction has completed. */
    private void lockAndRequire(String diaryLockMode) {
        try {
            jdbc.execute("SET LOCAL row_security = off");
            jdbc.execute("LOCK TABLE public.diary_generations IN ACCESS SHARE MODE");
            jdbc.execute("LOCK TABLE public.diaries IN " + diaryLockMode + " MODE");
            requireOrdinaryTable("diaries");
            requireOrdinaryTable("diary_generations");
            requireColumn("diaries", "id", "uuid");
            requireColumn("diaries", "user_id", "uuid");
            requireColumn("diaries", "diary_date", "date");
            requireColumn("diaries", "source_text", "text");
            requireColumn("diaries", "source_text_version", "integer");
            requireColumn("diary_generations", "id", "uuid");
            requireColumn("diary_generations", "diary_id", "uuid");
            requireSingleColumnKey("diaries", "id", "p");
            requireSingleColumnKey("diary_generations", "id", "p");
            requireSingleColumnKey("diary_generations", "diary_id", "u");
        } catch (SQLException | RuntimeException exception) {
            throw new SchemaViolationException();
        }
    }

    private void requireOrdinaryTable(String table) throws SQLException {
        try (PreparedStatement statement = jdbc.prepare("""
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

    private void requireColumn(String table, String column, String type) throws SQLException {
        try (PreparedStatement statement = jdbc.prepare("""
                SELECT a.attnotnull, a.attgenerated = '' AND a.attidentity = '' AS plain_column,
                       n.nspname = 'pg_catalog' AS built_in,
                       pg_catalog.format_type(a.atttypid, a.atttypmod) AS formatted_type
                FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
                WHERE a.attrelid = pg_catalog.to_regclass(?) AND a.attname = ?
                  AND a.attnum > 0 AND NOT a.attisdropped
                """)) {
            statement.setString(1, "public." + table);
            statement.setString(2, column);
            try (ResultSet row = statement.executeQuery()) {
                require(row.next() && row.getBoolean("attnotnull") && row.getBoolean("plain_column")
                        && row.getBoolean("built_in") && type.equals(row.getString("formatted_type")));
            }
        }
    }

    private void requireSingleColumnKey(String table, String column, String kind) throws SQLException {
        try (PreparedStatement statement = jdbc.prepare("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_constraint c JOIN pg_catalog.pg_attribute a
                      ON a.attrelid = c.conrelid AND a.attname = ? AND NOT a.attisdropped
                    WHERE c.conrelid = pg_catalog.to_regclass(?) AND c.contype::text = ?
                      AND c.convalidated AND NOT c.condeferrable AND c.conkey = ARRAY[a.attnum]
                      AND a.attnotnull AND a.atttypid = 'pg_catalog.uuid'::regtype
                )
                """)) {
            statement.setString(1, column);
            statement.setString(2, "public." + table);
            statement.setString(3, kind);
            require(jdbc.readBoolean(statement));
        }
    }

    static final class SchemaViolationException extends RuntimeException {
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new DiarySourceTextMigrationException();
        }
    }

    private record Constraint(boolean exists, boolean exact, boolean validated) {
    }
}
