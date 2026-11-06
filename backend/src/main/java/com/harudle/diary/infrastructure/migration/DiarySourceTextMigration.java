package com.harudle.diary.infrastructure.migration;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.infrastructure.migration.DiarySourceTextMigrationSession.SqlOperation;
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
 * An uncertain transaction or advisory-lock result aborts the connection instead of returning it for reuse.
 * CLI operations share an advisory lock; live requests remain protected by generation-then-diary row locks.
 * No plaintext, ciphertext, fingerprints or SQL diagnostics are included in returned failures.
 */
public final class DiarySourceTextMigration {

    public static final long EXECUTION_LOCK_KEY = 0x48415255444c4533L;

    private static final int MAX_BATCH_SIZE = 1_000;
    private static final int MAX_BATCHES = 10_000;
    private static final String JOINED_ROWS = """
            SELECT d.id, d.user_id, d.diary_date, d.source_text, d.source_text_version,
                   g.id AS generation_id, g.idempotency_key, g.request_fingerprint
            FROM public.diaries d LEFT JOIN public.diary_generations g ON g.diary_id = d.id
            """;

    private final DiarySourceTextMigrationSession jdbc;
    private final DiarySourceTextMigrationSchema schema;
    private final DiarySourceTextCipher cipher;
    private final RequestFingerprintGenerator fingerprints;

    public DiarySourceTextMigration(
            Connection connection,
            DiarySourceTextCipher cipher,
            RequestFingerprintGenerator fingerprints
    ) {
        require(connection != null && cipher != null && fingerprints != null);
        this.jdbc = new DiarySourceTextMigrationSession(connection);
        this.schema = new DiarySourceTextMigrationSchema(jdbc);
        this.cipher = cipher;
        this.fingerprints = fingerprints;
    }

    public Status status() {
        return jdbc.safely(() -> inspect(this::readStatus));
    }

    /** Row conversion requires fresh READ_COMMITTED snapshots without changing the caller's connection settings. */
    public MigrationResult migrate(int batchSize, int maxBatches) {
        return jdbc.safely(() -> {
            if (jdbc.transactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new DiarySourceTextMigrationException(
                        DiarySourceTextMigrationException.Reason.ISOLATION_LEVEL_UNSUPPORTED
                );
            }
            return jdbc.exclusively(() -> migrateLocked(batchSize, maxBatches));
        });
    }

    public VerificationResult verify(int batchSize) {
        return jdbc.safely(() -> jdbc.exclusively(() -> inspect(() -> verifyLocked(batchSize))));
    }

    public VerificationResult finalizeMigration(int batchSize) {
        return jdbc.safely(() -> jdbc.exclusively(() -> finalizeLocked(batchSize)));
    }

    /** Requires a validated v1-only CHECK, a non-null version with no default, and no legacy or unknown rows. */
    public void requireReady() {
        jdbc.safely(() -> {
            inspect(() -> {
                requireReadyInternal();
                return null;
            });
            return null;
        });
    }

    private MigrationResult migrateLocked(int batchSize, int maxBatches) throws SQLException {
        validateBatchSize(batchSize);
        require(maxBatches > 0 && maxBatches <= MAX_BATCHES);
        Counters counters = new Counters();
        UUID cursor = null;
        for (int batch = 0; batch < maxBatches; batch++) {
            UUID pageCursor = cursor;
            List<UUID> candidates = inspect(() -> legacyCandidates(pageCursor, batchSize));
            migrateCandidates(candidates, counters);
            if (candidates.size() < batchSize) {
                break;
            }
            cursor = candidates.getLast();
        }
        return new MigrationResult(counters.attempted, counters.migrated, counters.skippedProcessing,
                counters.failed, counters.orphanMigrated, inspect(this::readStatus));
    }

    private void migrateCandidates(List<UUID> candidates, Counters counters) {
        for (UUID diaryId : candidates) {
            counters.record(migrateCandidate(diaryId));
        }
    }

    private Outcome migrateCandidate(UUID diaryId) {
        try {
            return jdbc.transaction(() -> {
                schema.lockAndRequire(false);
                return migrateRow(diaryId);
            });
        } catch (SQLException | RuntimeException exception) {
            if (jdbc.isUnusable() || exception instanceof DiarySourceTextMigrationSchema.SchemaViolationException) {
                throw new DiarySourceTextMigrationException();
            }
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
        require("PENDING".equals(generation.status()) || "SUCCEEDED".equals(generation.status())
                || "FAILED".equals(generation.status()));
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
        String sql = "SELECT id FROM public.diaries WHERE source_text_version = 0"
                + (cursor == null ? "" : " AND id > ?") + " ORDER BY id LIMIT ?";
        try (PreparedStatement statement = jdbc.prepare(sql)) {
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
        try (PreparedStatement statement = jdbc.prepare("""
                SELECT id, idempotency_key, request_fingerprint, status
                FROM public.diary_generations WHERE diary_id = ? FOR UPDATE
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
        try (PreparedStatement statement = jdbc.prepare("""
                SELECT id, user_id, diary_date, source_text, source_text_version
                FROM public.diaries WHERE id = ? FOR UPDATE
                """)) {
            statement.setObject(1, diaryId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? readDiary(row) : null;
            }
        }
    }

    private boolean hasGeneration(UUID diaryId) throws SQLException {
        try (PreparedStatement statement = jdbc.prepare(
                "SELECT EXISTS (SELECT 1 FROM public.diary_generations WHERE diary_id = ?)")) {
            statement.setObject(1, diaryId);
            return jdbc.readBoolean(statement);
        }
    }

    private void updateDiary(UUID diaryId, String encrypted) throws SQLException {
        try (PreparedStatement statement = jdbc.prepare("""
                UPDATE public.diaries SET source_text = ?, source_text_version = 1
                WHERE id = ? AND source_text_version = 0
                """)) {
            statement.setString(1, encrypted);
            statement.setObject(2, diaryId);
            require(statement.executeUpdate() == 1);
        }
    }

    private void updateFingerprint(Generation generation, String fingerprint) throws SQLException {
        try (PreparedStatement statement = jdbc.prepare("""
                UPDATE public.diary_generations SET request_fingerprint = ? WHERE id = ? AND request_fingerprint = ?
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
        try (PreparedStatement statement = jdbc.prepare(sql)) {
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
        VerificationResult verified = inspect(() -> verifyLocked(batchSize));
        if (verified.failed() > 0 || verified.remaining().legacyRows() > 0
                || verified.remaining().unsupportedRows() > 0) {
            return verified;
        }
        long blocked = jdbc.transaction(() -> {
            schema.lockAndRequire(true);
            return finalizeSchema();
        });
        return new VerificationResult(verified.checked(), blocked, inspect(this::readStatus));
    }

    private long finalizeSchema() throws SQLException {
        Status current = readStatus();
        long invalid = current.legacyRows() + current.unsupportedRows();
        if (invalid > 0) {
            return invalid;
        }
        schema.finalizeConstraints();
        requireReadyInternal();
        return 0;
    }

    private void requireReadyInternal() throws SQLException {
        Status state = readStatus();
        require(state.finalized() && state.legacyRows() == 0 && state.unsupportedRows() == 0);
    }

    private Status readStatus() throws SQLException {
        try (PreparedStatement statement = jdbc.prepare("""
                SELECT count(*) FILTER (WHERE d.source_text_version = 0) AS legacy,
                       count(*) FILTER (WHERE d.source_text_version = 1) AS encrypted,
                       count(*) FILTER (WHERE d.source_text_version IS NULL
                                             OR d.source_text_version NOT IN (0, 1)) AS unsupported,
                       count(*) FILTER (WHERE d.source_text_version = 0 AND g.status = 'PROCESSING') AS processing,
                       count(*) FILTER (WHERE g.id IS NULL) AS orphans
                FROM public.diaries d LEFT JOIN public.diary_generations g ON g.diary_id = d.id
                """)) {
            try (ResultSet row = statement.executeQuery()) {
                require(row.next());
                return new Status(row.getLong("legacy"), row.getLong("encrypted"), row.getLong("unsupported"),
                        row.getLong("processing"), row.getLong("orphans"), schema.isFinalized());
            }
        }
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

    private <T> T inspect(SqlOperation<T> operation) throws SQLException {
        return jdbc.transaction(() -> {
            schema.lockAndRequire(false);
            return operation.run();
        });
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
}
