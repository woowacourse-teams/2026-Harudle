package com.harudle.diary.infrastructure.migration;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Uses a dedicated auto-commit connection, leaving healthy connections open for their owner.
 * An uncertain rollback or advisory-lock result aborts the connection instead of returning it for reuse.
 * CLI operations share an advisory lock; live requests remain protected by generation-then-diary row locks.
 * No plaintext, ciphertext, fingerprints or SQL diagnostics are included in returned failures.
 */
public final class DiarySourceTextMigration {

    public static final long EXECUTION_LOCK_KEY = 0x48415255444c4533L;

    private static final int QUERY_TIMEOUT_SECONDS = 10;
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_BATCHES = 10_000;
    private static final String ENCRYPTED_ONLY_CONSTRAINT = "ck_diaries_source_text_encrypted_only";
    private static final String JOINED_ROWS = """
            SELECT d.id, d.user_id, d.diary_date, d.source_text, d.source_text_version,
                   g.id AS generation_id, g.idempotency_key, g.request_fingerprint
            FROM diaries d LEFT JOIN diary_generations g ON g.diary_id = d.id
            """;

    private final Connection connection;
    private final DiarySourceTextCipher cipher;
    private final RequestFingerprintGenerator fingerprints;
    private boolean unusable;

    public DiarySourceTextMigration(
            Connection connection,
            DiarySourceTextCipher cipher,
            RequestFingerprintGenerator fingerprints
    ) {
        require(connection != null && cipher != null && fingerprints != null);
        this.connection = connection;
        this.cipher = cipher;
        this.fingerprints = fingerprints;
    }

    public Status status() {
        return safely(this::readStatus);
    }

    public MigrationResult migrate(int batchSize, int maxBatches) {
        return safely(() -> exclusively(() -> migrateLocked(batchSize, maxBatches)));
    }

    public VerificationResult verify(int batchSize) {
        return safely(() -> exclusively(() -> verifyLocked(batchSize)));
    }

    public VerificationResult finalizeMigration(int batchSize) {
        return safely(() -> exclusively(() -> finalizeLocked(batchSize)));
    }

    /** Requires a validated v1-only CHECK, a non-null version with no default, and no legacy or unknown rows. */
    public void requireReady() {
        safely(() -> {
            requireReadyInternal();
            return null;
        });
    }

    private MigrationResult migrateLocked(int batchSize, int maxBatches) throws SQLException {
        validateBatchSize(batchSize);
        require(maxBatches > 0 && maxBatches <= MAX_BATCHES);
        Counters counters = new Counters();
        UUID cursor = null;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<UUID> candidates = legacyCandidates(cursor, batchSize);
            migrateCandidates(candidates, counters);
            if (candidates.size() < batchSize) {
                break;
            }
            cursor = candidates.getLast();
        }
        return new MigrationResult(counters.attempted, counters.migrated, counters.skippedProcessing,
                counters.failed, counters.orphanMigrated, readStatus());
    }

    private void migrateCandidates(List<UUID> candidates, Counters counters) {
        for (UUID diaryId : candidates) {
            counters.record(migrateCandidate(diaryId));
        }
    }

    private Outcome migrateCandidate(UUID diaryId) {
        try {
            return transaction(() -> migrateRow(diaryId));
        } catch (SQLException | RuntimeException exception) {
            return Outcome.FAILED;
        }
    }

    private Outcome migrateRow(UUID diaryId) throws SQLException {
        Generation generation = lockGeneration(diaryId);
        StoredDiary diary = lockDiary(diaryId);
        if (diary == null || Integer.valueOf(1).equals(diary.version())) {
            return Outcome.UNCHANGED;
        }
        require(Integer.valueOf(0).equals(diary.version()));
        if (generation != null && "PROCESSING".equals(generation.status())) {
            return Outcome.PROCESSING;
        }
        return convertLegacyRow(diary, generation);
    }

    private Outcome convertLegacyRow(StoredDiary diary, Generation generation) throws SQLException {
        String normalized = Diary.normalizeSourceText(diary.sourceText());
        require(normalized.equals(diary.sourceText()));
        validateGeneration(diary, generation);
        String encrypted = cipher.encrypt(diary.id(), diary.userId(), diary.sourceText());
        String protectedFingerprint = protectNewFingerprint(diary, generation, encrypted);
        updateDiary(diary.id(), encrypted);
        if (generation != null) {
            updateFingerprint(generation, protectedFingerprint);
            return Outcome.MIGRATED;
        }
        return Outcome.ORPHAN_MIGRATED;
    }

    private void validateGeneration(StoredDiary diary, Generation generation) throws SQLException {
        if (generation == null) {
            // Recheck after locking the orphan; a newly committed FK reference must not be silently ignored.
            require(!hasGeneration(diary.id()));
            return;
        }
        require("SUCCEEDED".equals(generation.status()) || "FAILED".equals(generation.status()));
        require(equalFingerprint(rawFingerprint(diary, generation.idempotencyKey()), generation.fingerprint()));
    }

    private String protectNewFingerprint(StoredDiary diary, Generation generation, String encrypted) {
        if (generation == null) {
            return null;
        }
        return cipher.protectRequestFingerprint(diary.id(), diary.userId(), encrypted,
                DiarySourceTextCipher.CURRENT_VERSION, rawFingerprint(diary, generation.idempotencyKey()));
    }

    private List<UUID> legacyCandidates(UUID cursor, int batchSize) throws SQLException {
        String sql = "SELECT id FROM diaries WHERE source_text_version = 0"
                + (cursor == null ? "" : " AND id > ?") + " ORDER BY id LIMIT ?";
        try (PreparedStatement statement = prepare(sql)) {
            int limitIndex = bindCursor(statement, cursor);
            statement.setInt(limitIndex, batchSize);
            return readIds(statement);
        }
    }

    private List<UUID> readIds(PreparedStatement statement) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ids.add(rows.getObject(1, UUID.class));
            }
        }
        return ids;
    }

    private Generation lockGeneration(UUID diaryId) throws SQLException {
        try (PreparedStatement statement = prepare("""
                SELECT id, idempotency_key, request_fingerprint, status
                FROM diary_generations WHERE diary_id = ? FOR UPDATE
                """)) {
            statement.setObject(1, diaryId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new Generation(row.getObject("id", UUID.class),
                        row.getObject("idempotency_key", UUID.class), row.getString("request_fingerprint"),
                        row.getString("status")) : null;
            }
        }
    }

    private StoredDiary lockDiary(UUID diaryId) throws SQLException {
        try (PreparedStatement statement = prepare("""
                SELECT id, user_id, diary_date, source_text, source_text_version
                FROM diaries WHERE id = ? FOR UPDATE
                """)) {
            statement.setObject(1, diaryId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? readDiary(row) : null;
            }
        }
    }

    private boolean hasGeneration(UUID diaryId) throws SQLException {
        try (PreparedStatement statement = prepare(
                "SELECT EXISTS (SELECT 1 FROM diary_generations WHERE diary_id = ?)")) {
            statement.setObject(1, diaryId);
            return readBoolean(statement);
        }
    }

    private void updateDiary(UUID diaryId, String encrypted) throws SQLException {
        try (PreparedStatement statement = prepare("""
                UPDATE diaries SET source_text = ?, source_text_version = 1
                WHERE id = ? AND source_text_version = 0
                """)) {
            statement.setString(1, encrypted);
            statement.setObject(2, diaryId);
            require(statement.executeUpdate() == 1);
        }
    }

    private void updateFingerprint(Generation generation, String fingerprint) throws SQLException {
        try (PreparedStatement statement = prepare("""
                UPDATE diary_generations SET request_fingerprint = ? WHERE id = ? AND request_fingerprint = ?
                """)) {
            statement.setString(1, fingerprint);
            statement.setObject(2, generation.id());
            statement.setString(3, generation.fingerprint());
            require(statement.executeUpdate() == 1);
        }
    }

    private VerificationResult verifyLocked(int batchSize) throws SQLException {
        validateBatchSize(batchSize);
        long checked = 0;
        long failed = 0;
        UUID cursor = null;
        List<VerificationRow> rows;
        do {
            rows = verificationRows(cursor, batchSize);
            checked += rows.size();
            failed += rows.stream().filter(row -> !verifyRow(row)).count();
            if (!rows.isEmpty()) {
                cursor = rows.getLast().diary().id();
            }
        } while (rows.size() == batchSize);
        return new VerificationResult(checked, failed, readStatus());
    }

    private List<VerificationRow> verificationRows(UUID cursor, int batchSize) throws SQLException {
        String sql = JOINED_ROWS + (cursor == null ? "" : " WHERE d.id > ?") + " ORDER BY d.id LIMIT ?";
        try (PreparedStatement statement = prepare(sql)) {
            statement.setInt(bindCursor(statement, cursor), batchSize);
            return readVerificationRows(statement);
        }
    }

    private List<VerificationRow> readVerificationRows(PreparedStatement statement) throws SQLException {
        List<VerificationRow> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new VerificationRow(readDiary(result), result.getObject("generation_id", UUID.class),
                        result.getObject("idempotency_key", UUID.class), result.getString("request_fingerprint")));
            }
        }
        return rows;
    }

    private boolean verifyRow(VerificationRow row) {
        try {
            require(Integer.valueOf(1).equals(row.diary().version()));
            String plaintext = cipher.decrypt(row.diary().id(), row.diary().userId(), row.diary().sourceText(), 1);
            return verifyRowFingerprint(row, plaintext);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean verifyRowFingerprint(VerificationRow row, String plaintext) {
        if (row.generationId() == null) {
            return true;
        }
        StoredDiary diary = row.diary();
        String raw = fingerprints.generate(new GenerateDiaryImageCommand(
                diary.userId(), diary.id(), diary.date(), plaintext, row.idempotencyKey()
        ));
        String expected = cipher.protectRequestFingerprint(diary.id(), diary.userId(), diary.sourceText(), 1, raw);
        return equalFingerprint(expected, row.fingerprint());
    }

    private VerificationResult finalizeLocked(int batchSize) throws SQLException {
        VerificationResult verified = verifyLocked(batchSize);
        if (verified.failed() > 0 || verified.remaining().legacyRows() > 0
                || verified.remaining().unsupportedRows() > 0) {
            return verified;
        }
        long blocked = transaction(this::finalizeSchema);
        return new VerificationResult(verified.checked(), blocked, readStatus());
    }

    private long finalizeSchema() throws SQLException {
        execute("LOCK TABLE diaries IN ACCESS EXCLUSIVE MODE");
        Status current = readStatus();
        long invalid = current.legacyRows() + current.unsupportedRows();
        if (invalid > 0) {
            return invalid;
        }
        Constraint constraint = readConstraint();
        if (!constraint.exists()) {
            execute("ALTER TABLE diaries ADD CONSTRAINT " + ENCRYPTED_ONLY_CONSTRAINT
                    + " CHECK (source_text_version = 1) NOT VALID");
        } else {
            require(constraint.exact());
        }
        execute("ALTER TABLE diaries VALIDATE CONSTRAINT " + ENCRYPTED_ONLY_CONSTRAINT);
        execute("ALTER TABLE diaries ALTER COLUMN source_text_version DROP DEFAULT");
        requireReadyInternal();
        return 0;
    }

    private void requireReadyInternal() throws SQLException {
        Status state = readStatus();
        require(state.finalized() && state.legacyRows() == 0 && state.unsupportedRows() == 0);
    }

    private Status readStatus() throws SQLException {
        try (PreparedStatement statement = prepare("""
                SELECT count(*) FILTER (WHERE d.source_text_version = 0) AS legacy,
                       count(*) FILTER (WHERE d.source_text_version = 1) AS encrypted,
                       count(*) FILTER (WHERE d.source_text_version IS NULL
                                             OR d.source_text_version NOT IN (0, 1)) AS unsupported,
                       count(*) FILTER (WHERE d.source_text_version = 0 AND g.status = 'PROCESSING') AS processing,
                       count(*) FILTER (WHERE g.id IS NULL) AS orphans
                FROM diaries d LEFT JOIN diary_generations g ON g.diary_id = d.id
                """)) {
            try (ResultSet row = statement.executeQuery()) {
                require(row.next());
                return new Status(row.getLong("legacy"), row.getLong("encrypted"), row.getLong("unsupported"),
                        row.getLong("processing"), row.getLong("orphans"), isSchemaFinalized());
            }
        }
    }

    private boolean isSchemaFinalized() throws SQLException {
        Constraint constraint = readConstraint();
        try (PreparedStatement statement = prepare("""
                SELECT a.attnotnull, a.atthasdef FROM pg_attribute a
                WHERE a.attrelid = 'diaries'::regclass AND a.attname = 'source_text_version' AND NOT a.attisdropped
                """)) {
            try (ResultSet row = statement.executeQuery()) {
                require(row.next());
                return constraint.exists() && constraint.exact() && constraint.validated()
                        && row.getBoolean("attnotnull") && !row.getBoolean("atthasdef");
            }
        }
    }

    private Constraint readConstraint() throws SQLException {
        try (PreparedStatement statement = prepare("""
                SELECT c.contype, c.convalidated, c.connoinherit,
                       pg_get_expr(c.conbin, c.conrelid) AS expression,
                       array_length(c.conkey, 1) AS columns,
                       a.attnum = ANY(c.conkey) AS correct_column
                FROM pg_constraint c JOIN pg_attribute a
                  ON a.attrelid = c.conrelid AND a.attname = 'source_text_version' AND NOT a.attisdropped
                WHERE c.conrelid = 'diaries'::regclass AND c.conname = ?
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

    private StoredDiary readDiary(ResultSet row) throws SQLException {
        return new StoredDiary(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                row.getObject("diary_date", LocalDate.class), row.getString("source_text"),
                row.getObject("source_text_version", Integer.class));
    }

    private String rawFingerprint(StoredDiary diary, UUID idempotencyKey) {
        return fingerprints.generate(new GenerateDiaryImageCommand(
                diary.userId(), diary.id(), diary.date(), diary.sourceText(), idempotencyKey
        ));
    }

    private boolean equalFingerprint(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private int bindCursor(PreparedStatement statement, UUID cursor) throws SQLException {
        if (cursor == null) {
            return 1;
        }
        statement.setObject(1, cursor);
        return 2;
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        require(!unusable);
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return statement;
    }

    private void execute(String sql) throws SQLException {
        try (PreparedStatement statement = prepare(sql)) {
            statement.execute();
        }
    }

    private boolean readBoolean(PreparedStatement statement) throws SQLException {
        try (ResultSet row = statement.executeQuery()) {
            require(row.next());
            return row.getBoolean(1);
        }
    }

    private <T> T transaction(SqlOperation<T> operation) throws SQLException {
        require(!unusable);
        connection.setAutoCommit(false);
        try {
            execute("SET LOCAL lock_timeout = '2s'");
            execute("SET LOCAL statement_timeout = '10s'");
            T result = operation.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            rollbackSafely();
            throw exception;
        } finally {
            restoreAutoCommit();
        }
    }

    private void rollbackSafely() throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            abort();
            throw new DiarySourceTextMigrationException();
        }
    }

    private void restoreAutoCommit() throws SQLException {
        if (unusable || connection.isClosed()) {
            return;
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException exception) {
            abort();
            throw new DiarySourceTextMigrationException();
        }
    }

    private <T> T exclusively(SqlOperation<T> operation) throws SQLException {
        acquireExecutionLock();
        try {
            return operation.run();
        } finally {
            releaseExecutionLock();
        }
    }

    private void acquireExecutionLock() throws SQLException {
        boolean acquired;
        try {
            acquired = advisory("SELECT pg_try_advisory_lock(?)");
        } catch (SQLException | RuntimeException exception) {
            abort();
            throw exception;
        }
        require(acquired);
    }

    private void releaseExecutionLock() throws SQLException {
        if (unusable || connection.isClosed()) {
            throw new DiarySourceTextMigrationException();
        }
        try {
            require(advisory("SELECT pg_advisory_unlock(?)"));
        } catch (SQLException | RuntimeException exception) {
            abort();
            throw new DiarySourceTextMigrationException();
        }
    }

    private boolean advisory(String sql) throws SQLException {
        try (PreparedStatement statement = prepare(sql)) {
            statement.setLong(1, EXECUTION_LOCK_KEY);
            return readBoolean(statement);
        }
    }

    private void abort() {
        unusable = true;
        try {
            connection.abort(Runnable::run);
        } catch (SQLException ignored) {
            // The dedicated connection must never be returned to a pool after an uncertain lock/rollback result.
        }
    }

    private <T> T safely(SqlOperation<T> operation) {
        try {
            require(!unusable && !connection.isClosed() && connection.getAutoCommit());
            return operation.run();
        } catch (SQLException | RuntimeException exception) {
            throw new DiarySourceTextMigrationException();
        }
    }

    private void validateBatchSize(int batchSize) {
        require(batchSize > 0 && batchSize <= MAX_BATCH_SIZE);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new DiarySourceTextMigrationException();
        }
    }

    public record Status(long legacyRows, long encryptedRows, long unsupportedRows,
                         long processingLegacyRows, long orphanRows, boolean finalized) {
    }

    public record MigrationResult(long attempted, long migrated, long skippedProcessing,
                                  long failed, long orphanMigrated, Status remaining) {
    }

    public record VerificationResult(long checked, long failed, Status remaining) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {

        T run() throws SQLException;
    }

    private enum Outcome {
        MIGRATED, ORPHAN_MIGRATED, PROCESSING, FAILED, UNCHANGED
    }

    private static final class Counters {

        private long attempted;
        private long migrated;
        private long skippedProcessing;
        private long failed;
        private long orphanMigrated;

        private void record(Outcome outcome) {
            attempted++;
            switch (outcome) {
                case MIGRATED -> migrated++;
                case ORPHAN_MIGRATED -> {
                    migrated++;
                    orphanMigrated++;
                }
                case PROCESSING -> skippedProcessing++;
                case FAILED -> failed++;
                case UNCHANGED -> { }
            }
        }
    }

    private record StoredDiary(UUID id, UUID userId, LocalDate date, String sourceText, Integer version) {

        @Override
        public String toString() {
            return "StoredDiary[redacted]";
        }
    }

    private record Generation(UUID id, UUID idempotencyKey, String fingerprint, String status) {

        @Override
        public String toString() {
            return "Generation[redacted]";
        }
    }

    private record VerificationRow(StoredDiary diary, UUID generationId, UUID idempotencyKey, String fingerprint) {

        @Override
        public String toString() {
            return "VerificationRow[redacted]";
        }
    }

    private record Constraint(boolean exists, boolean exact, boolean validated) {
    }
}
