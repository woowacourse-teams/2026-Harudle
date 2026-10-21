package com.harudle.diary.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.infrastructure.crypto.AesGcmDiarySourceTextCipher;
import com.harudle.diary.infrastructure.crypto.DiarySourceTextEncryptionProperties;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DiarySourceTextMigrationTest {

    private static final UUID USER_ID = UUID.fromString("292d3ad3-dc43-486d-bdeb-15be328a69dc");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 10, 20);
    private static final Instant CREATED_AT = Instant.parse("2026-10-20T01:00:00Z");
    private static final String OLD_KEY = key((byte) 1);
    private static final String NEW_KEY = key((byte) 2);
    private static final String FAILURE_MESSAGE = "일기 원문 전환 작업에 실패했습니다.";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine")
    );

    private final RequestFingerprintGenerator fingerprints = new RequestFingerprintGenerator();
    private final DiarySourceTextCipher cipher = cipher("old", Map.of("old", OLD_KEY));

    private Connection connection;
    private DiarySourceTextMigration migration;
    private long promptId;
    private int nextDiaryId;

    static void migrateSchema() {
        Flyway.configure()
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
    }

    @BeforeEach
    void setUp() throws SQLException {
        Flyway.configure()
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false).load().clean();
        migrateSchema();
        connection = openConnection();
        execute("DROP TRIGGER IF EXISTS migration_test_fingerprint_failure ON diary_generations");
        execute("DROP FUNCTION IF EXISTS migration_test_reject_fingerprint()");
        execute("ALTER TABLE diaries DROP CONSTRAINT IF EXISTS ck_diaries_source_text_encrypted_only");
        execute("ALTER TABLE diaries ALTER COLUMN source_text_version SET DEFAULT 0");
        execute("TRUNCATE TABLE users, generation_prompts CASCADE");
        execute("ALTER TABLE diaries ALTER COLUMN source_text_version SET NOT NULL");
        execute("ALTER TABLE diaries DROP CONSTRAINT IF EXISTS ck_diaries_source_text_version");
        execute("""
                ALTER TABLE diaries ADD CONSTRAINT ck_diaries_source_text_version
                CHECK (source_text_version IN (0, 1))
                """);
        execute("INSERT INTO users (id, name) VALUES (?, ?)", USER_ID, "전환 테스트 사용자");
        promptId = ((Number) scalar("""
                INSERT INTO generation_prompts (
                    storyboard_prompt_text, image_style_prompt_text, image_asset_object_key
                ) VALUES ('story', 'style', 'references/migration.png') RETURNING id
                """)).longValue();
        nextDiaryId = 1;
        migration = new DiarySourceTextMigration(connection, cipher, fingerprints);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
            connection.close();
        }
    }

    @Test
    @DisplayName("성공·실패·삭제 및 생성 기록 없는 원문을 전환하고 다른 업무 데이터는 보존한다")
    void migrateAllLegacyDiaryKindsWithMatchingFingerprints() throws SQLException {
        Fixture succeeded = insertLegacy("친구와 보낸 하루 😀", "SUCCEEDED", false);
        Fixture failed = insertLegacy("실패해도 원문은 보호한다", "FAILED", true);
        Fixture deleted = insertLegacy("삭제한 일기의 원문", "SUCCEEDED", true);
        Fixture orphan = insertLegacy("old:암호문처럼 보이지만 평문인 내용", null, false);
        List<Object> before = businessState(failed.diaryId());

        var result = migration.migrate(2, 10);

        assertThat(result.attempted()).isEqualTo(4);
        assertThat(result.migrated()).isEqualTo(4);
        assertThat(result.orphanMigrated()).isEqualTo(1);
        assertThat(result.skippedProcessing()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.remaining().legacyRows()).isZero();
        assertThat(result.remaining().encryptedRows()).isEqualTo(4);
        assertThat(result.remaining().orphanRows()).isEqualTo(1);
        for (Fixture fixture : List.of(succeeded, failed, deleted, orphan)) {
            assertEncrypted(fixture, cipher);
        }
        assertThat(businessState(failed.diaryId())).isEqualTo(before);
        assertThat(scalar("SELECT COUNT(*) FROM diary_generations")).isEqualTo(3L);
        assertThat(scalar("SELECT COUNT(*) FROM daily_generation_usage")).isEqualTo(0L);
        assertThat(migration.verify(2).failed()).isZero();
        assertConnectionRetained();
    }

    @Test
    @DisplayName("동일 배치를 재실행해도 이미 전환한 암호문과 논스 및 HMAC을 다시 만들지 않는다")
    void rerunWithoutReencryptingOrDoubleProtectingFingerprints() throws SQLException {
        Fixture fixture = insertLegacy("반복 실행할 일기", "SUCCEEDED", false);
        migration.migrate(1, 1);
        Stored before = stored(fixture.diaryId());

        var rerun = migration.migrate(1, 1);

        assertThat(rerun.attempted()).isZero();
        assertThat(rerun.migrated()).isZero();
        assertThat(stored(fixture.diaryId())).isEqualTo(before);
        assertEncrypted(fixture, cipher);
    }

    @Test
    @DisplayName("배치 한도로 중단한 뒤 재실행하면 커밋한 행은 유지하고 남은 행만 전환한다")
    void resumeAfterBoundedPartialRun() throws SQLException {
        Fixture first = insertLegacy("첫 번째", "SUCCEEDED", false);
        Fixture second = insertLegacy("두 번째", "FAILED", true);
        Fixture third = insertLegacy("세 번째", null, false);

        var partial = migration.migrate(1, 1);
        Stored firstCommitted = stored(first.diaryId());
        var resumed = migration.migrate(2, 10);

        assertThat(partial.migrated()).isEqualTo(1);
        assertThat(partial.remaining().legacyRows()).isEqualTo(2);
        assertThat(resumed.migrated()).isEqualTo(2);
        assertThat(resumed.remaining().legacyRows()).isZero();
        assertThat(stored(first.diaryId())).isEqualTo(firstCommitted);
        assertEncrypted(second, cipher);
        assertEncrypted(third, cipher);
    }

    @Test
    @DisplayName("잘못된 과거 원문과 SHA는 보정하지 않고 집계하며 수정 후 재실행할 수 있다")
    void retainMalformedLegacyRowsAndResumeAfterRepair() throws SQLException {
        Fixture padded = insertLegacy("  앞뒤 공백을 자동 삭제하면 안 된다  ", "SUCCEEDED", false);
        Fixture mismatch = insertLegacy("원문과 지문이 달라진 일기", "SUCCEEDED", false);
        Fixture blank = insertLegacy("원래 유효했던 일기", "FAILED", true);
        Fixture good = insertLegacy("계속 처리할 정상 일기", "SUCCEEDED", false);
        execute("UPDATE diary_generations SET request_fingerprint = ? WHERE diary_id = ?",
                "a".repeat(64), mismatch.diaryId());
        execute("UPDATE diaries SET source_text = '   ' WHERE id = ?", blank.diaryId());
        Stored paddedBefore = stored(padded.diaryId());
        Stored mismatchBefore = stored(mismatch.diaryId());

        var result = migration.migrate(2, 10);
        var rejectedFinalize = migration.finalizeMigration(2);

        assertThat(result.attempted()).isEqualTo(4);
        assertThat(result.failed()).isEqualTo(3);
        assertThat(result.migrated()).isEqualTo(1);
        assertThat(result.remaining().legacyRows()).isEqualTo(3);
        assertThat(stored(padded.diaryId())).isEqualTo(paddedBefore);
        assertThat(stored(mismatch.diaryId())).isEqualTo(mismatchBefore);
        assertEncrypted(good, cipher);
        assertThat(rejectedFinalize.failed()).isEqualTo(3);
        assertThat(rejectedFinalize.remaining().finalized()).isFalse();
        assertThat(versionDefault()).isNotNull();

        execute("UPDATE diaries SET source_text = ? WHERE id = ?", padded.source().strip(), padded.diaryId());
        execute("UPDATE diary_generations SET request_fingerprint = ? WHERE diary_id = ?",
                fingerprints.generate(new GenerateDiaryImageCommand(
                        USER_ID, padded.diaryId(), DIARY_DATE, padded.source().strip(), padded.idempotencyKey()
                )), padded.diaryId());
        execute("UPDATE diary_generations SET request_fingerprint = ? WHERE diary_id = ?",
                rawFingerprint(mismatch), mismatch.diaryId());
        execute("UPDATE diaries SET source_text = ? WHERE id = ?", blank.source(), blank.diaryId());
        var resumed = migration.migrate(2, 10);
        assertThat(resumed.failed()).isZero();
        assertThat(resumed.migrated()).isEqualTo(3);
        assertThat(resumed.remaining().legacyRows()).isZero();
    }

    @Test
    @DisplayName("생성 지문 갱신이 실패하면 앞서 갱신한 일기 암호문과 버전도 함께 롤백한다")
    void rollBackFirstUpdateWhenFingerprintUpdateFails() throws SQLException {
        Fixture fixture = insertLegacy("두 번째 UPDATE 실패를 검증한다", "SUCCEEDED", false);
        Stored before = stored(fixture.diaryId());
        execute("""
                CREATE FUNCTION migration_test_reject_fingerprint() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected migration update failure'; END;
                $$
                """);
        execute("""
                CREATE TRIGGER migration_test_fingerprint_failure
                BEFORE UPDATE OF request_fingerprint ON diary_generations
                FOR EACH ROW EXECUTE FUNCTION migration_test_reject_fingerprint()
                """);

        var failed = migration.migrate(10, 1);

        assertThat(failed.failed()).isEqualTo(1);
        assertThat(failed.migrated()).isZero();
        assertThat(stored(fixture.diaryId())).isEqualTo(before);
        assertConnectionRetained();
        execute("DROP TRIGGER migration_test_fingerprint_failure ON diary_generations");
        assertThat(migration.migrate(10, 1).migrated()).isEqualTo(1);
        assertEncrypted(fixture, cipher);
    }

    @Test
    @DisplayName("처리 중 생성은 건너뛰되 뒤의 행도 처리하고 완료 판정에서는 남은 평문을 숨기지 않는다")
    void skipProcessingWithoutLosingItBehindTheBatchCursor() throws SQLException {
        Fixture processing = insertLegacy("생성 중인 일기", "PROCESSING", false);
        Fixture finished = insertLegacy("다음 순서의 완료된 일기", "SUCCEEDED", false);
        Stored before = stored(processing.diaryId());

        var first = migration.migrate(1, 10);

        assertThat(first.attempted()).isEqualTo(2);
        assertThat(first.skippedProcessing()).isEqualTo(1);
        assertThat(first.failed()).isZero();
        assertThat(first.migrated()).isEqualTo(1);
        assertThat(first.remaining().processingLegacyRows()).isEqualTo(1);
        assertThat(stored(processing.diaryId())).isEqualTo(before);
        assertEncrypted(finished, cipher);
        assertThat(migration.finalizeMigration(1).failed()).isEqualTo(1);
        assertThat(migration.status().finalized()).isFalse();

        execute("UPDATE diary_generations SET status = 'FAILED', error_code = 'AI_PROVIDER_ERROR' WHERE diary_id = ?",
                processing.diaryId());
        assertThat(migration.migrate(1, 10).migrated()).isEqualTo(1);
        assertEncrypted(processing, cipher);
    }

    @Test
    @DisplayName("활성 키를 바꿔 재개해도 기존 암호문은 보존하고 각 저장 키로 HMAC을 검증한다")
    void preserveOldCiphertextAcrossActiveKeyRotation() throws SQLException {
        Fixture first = insertLegacy("이전 키로 전환", "SUCCEEDED", false);
        Fixture second = insertLegacy("새 키로 전환", "SUCCEEDED", false);
        migration.migrate(1, 1);
        Stored oldStored = stored(first.diaryId());
        DiarySourceTextCipher rotated = cipher("new", Map.of("old", OLD_KEY, "new", NEW_KEY));
        var resumed = new DiarySourceTextMigration(connection, rotated, fingerprints);

        assertThat(resumed.migrate(10, 1).migrated()).isEqualTo(1);
        assertThat(stored(first.diaryId())).isEqualTo(oldStored);
        assertThat(stored(second.diaryId()).source()).startsWith("new:");
        assertEncrypted(first, rotated);
        assertEncrypted(second, rotated);
        assertThat(resumed.verify(1).failed()).isZero();
        var missingOldKey = new DiarySourceTextMigration(
                connection, cipher("new", Map.of("new", NEW_KEY)), fingerprints
        );
        assertThat(missingOldKey.verify(2).failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("v1 전수 검증은 원문 SHA 위조와 암호문 변조를 감지하고 완료 제약을 적용하지 않는다")
    void detectForgedFingerprintsAndDamagedEncryptedPayloads() throws SQLException {
        Fixture forged = insertLegacy("SHA를 다시 넣은 일기", "SUCCEEDED", false);
        Fixture damaged = insertLegacy("암호문을 바꾼 일기", "SUCCEEDED", false);
        Fixture orphan = insertLegacy("연결 없는 암호문도 검증한다", null, false);
        migration.migrate(10, 1);
        execute("UPDATE diary_generations SET request_fingerprint = ? WHERE diary_id = ?",
                rawFingerprint(forged), forged.diaryId());
        execute("UPDATE diaries SET source_text = ? WHERE id = ?",
                cipher.encrypt(UUID.randomUUID(), USER_ID, damaged.source()), damaged.diaryId());

        var verified = migration.verify(2);

        assertThat(verified.checked()).isEqualTo(3);
        assertThat(verified.failed()).isEqualTo(2);
        assertThat(verified.remaining().legacyRows()).isZero();
        assertThat(verified.remaining().orphanRows()).isEqualTo(1);
        assertEncrypted(orphan, cipher);
        assertThat(migration.finalizeMigration(2).failed()).isEqualTo(2);
        assertThat(migration.status().finalized()).isFalse();
        assertThat(versionDefault()).isNotNull();
    }

    @Test
    @DisplayName("지원하지 않는 저장 버전도 집계와 전수 검증에 포함하고 완료를 거절한다")
    void countUnsupportedVersionsAsVerificationFailures() throws SQLException {
        Fixture fixture = insertLegacy("지원하지 않는 버전", null, false);
        execute("ALTER TABLE diaries DROP CONSTRAINT ck_diaries_source_text_version");
        execute("UPDATE diaries SET source_text_version = 2 WHERE id = ?", fixture.diaryId());

        assertThat(migration.status().unsupportedRows()).isEqualTo(1);
        assertThat(migration.verify(1).checked()).isEqualTo(1);
        assertThat(migration.verify(1).failed()).isEqualTo(1);
        assertThat(migration.finalizeMigration(1).failed()).isEqualTo(1);
        assertThat(migration.status().finalized()).isFalse();
    }

    @Test
    @DisplayName("완료는 전수 검증 후 v1 전용 제약과 기본값 제거를 적용하고 재실행해도 안전하다")
    void finalizeOnlyAfterVerificationAndRequireAnExplicitVersionForFutureWrites() throws SQLException {
        Fixture fixture = insertLegacy("최종 전환", "SUCCEEDED", false);
        assertThatThrownBy(migration::requireReady).isInstanceOf(DiarySourceTextMigrationException.class);
        migration.migrate(1, 1);
        assertThatThrownBy(migration::requireReady).isInstanceOf(DiarySourceTextMigrationException.class);

        var finalized = migration.finalizeMigration(1);

        assertThat(finalized.checked()).isEqualTo(1);
        assertThat(finalized.failed()).isZero();
        assertThat(finalized.remaining().finalized()).isTrue();
        assertThat(versionDefault()).isNull();
        migration.requireReady();
        Stored before = stored(fixture.diaryId());
        assertThat(migration.finalizeMigration(1).remaining().finalized()).isTrue();
        assertThat(stored(fixture.diaryId())).isEqualTo(before);
        assertThatThrownBy(() -> execute(
                "INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), USER_ID, DIARY_DATE, "구버전 쓰기"
        )).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
                INSERT INTO diaries (id, user_id, diary_date, source_text, source_text_version)
                VALUES (?, ?, ?, ?, 0)
                """, UUID.randomUUID(), USER_ID, DIARY_DATE, "명시적 평문 쓰기"))
                .isInstanceOf(SQLException.class);
        insertEncryptedOrphan("전환 이후 정상 신규 쓰기");
        assertThat(migration.verify(2).failed()).isZero();
        assertConnectionRetained();
    }

    @Test
    @DisplayName("같은 이름의 느슨하거나 미검증인 제약을 준비 완료로 인정하지 않는다")
    void rejectForgedOrUnvalidatedReadinessConstraints() throws SQLException {
        execute("""
                ALTER TABLE diaries ADD CONSTRAINT ck_diaries_source_text_encrypted_only
                CHECK (source_text_version IN (0, 1))
                """);
        execute("ALTER TABLE diaries ALTER COLUMN source_text_version DROP DEFAULT");
        assertThatThrownBy(migration::requireReady).isInstanceOf(DiarySourceTextMigrationException.class);
        assertThatThrownBy(() -> migration.finalizeMigration(1))
                .isInstanceOf(DiarySourceTextMigrationException.class);

        execute("ALTER TABLE diaries DROP CONSTRAINT ck_diaries_source_text_encrypted_only");
        execute("""
                ALTER TABLE diaries ADD CONSTRAINT ck_diaries_source_text_encrypted_only
                CHECK (source_text_version = 1) NOT VALID
                """);
        assertThatThrownBy(migration::requireReady).isInstanceOf(DiarySourceTextMigrationException.class);
    }

    @Test
    @DisplayName("준비 판정은 정확한 v1 제약뿐 아니라 기본값 제거와 NOT NULL도 요구한다")
    void requireNoVersionDefaultAndANonNullableVersionColumn() throws SQLException {
        migration.finalizeMigration(1);
        migration.requireReady();

        execute("ALTER TABLE diaries ALTER COLUMN source_text_version SET DEFAULT 0");
        assertThat(migration.status().finalized()).isFalse();
        assertThatThrownBy(migration::requireReady).isInstanceOf(DiarySourceTextMigrationException.class);
        execute("ALTER TABLE diaries ALTER COLUMN source_text_version DROP DEFAULT");
        migration.requireReady();

        execute("ALTER TABLE diaries ALTER COLUMN source_text_version DROP NOT NULL");
        assertThatThrownBy(migration::status).isInstanceOf(DiarySourceTextMigrationException.class);
        assertThatThrownBy(migration::requireReady).isInstanceOf(DiarySourceTextMigrationException.class);
        execute("ALTER TABLE diaries ALTER COLUMN source_text_version SET NOT NULL");
        migration.requireReady();
    }

    @Test
    @DisplayName("NULL version을 허용한 복원 스키마는 행 집계 전에 전체 작업을 거절한다")
    void rejectNullableVersionSchemaInsteadOfReportingAnUnsupportedRow() throws SQLException {
        Fixture fixture = insertLegacy("NULL version의 원문은 그대로 둔다", null, false);
        execute("ALTER TABLE diaries ALTER COLUMN source_text_version DROP NOT NULL");
        execute("UPDATE diaries SET source_text_version = NULL WHERE id = ?", fixture.diaryId());

        assertThatThrownBy(migration::status).isExactlyInstanceOf(DiarySourceTextMigrationException.class);
        assertThatThrownBy(() -> migration.migrate(1, 1))
                .isExactlyInstanceOf(DiarySourceTextMigrationException.class);
        assertThatThrownBy(() -> migration.verify(1)).isExactlyInstanceOf(DiarySourceTextMigrationException.class);
        assertThatThrownBy(() -> migration.finalizeMigration(1))
                .isExactlyInstanceOf(DiarySourceTextMigrationException.class);
        assertThatThrownBy(migration::requireReady).isExactlyInstanceOf(DiarySourceTextMigrationException.class);

        assertThat(scalar("SELECT source_text FROM diaries WHERE id = ?", fixture.diaryId()))
                .isEqualTo(fixture.source());
        assertThat(scalar("SELECT source_text_version FROM diaries WHERE id = ?", fixture.diaryId())).isNull();
        assertConnectionRetained();
    }

    @Test
    @DisplayName("같은 이름이라도 NO INHERIT 또는 다른 열이 섞인 제약은 완료로 인정하지 않는다")
    void rejectConstraintsWhoseMetadataDoesNotMatchTheEncryptedOnlyContract() throws SQLException {
        execute("ALTER TABLE diaries ALTER COLUMN source_text_version DROP DEFAULT");
        for (String definition : List.of(
                "CHECK (source_text_version = 1) NO INHERIT",
                "CHECK (source_text_version = 1 AND user_id IS NOT NULL)"
        )) {
            execute("ALTER TABLE diaries ADD CONSTRAINT ck_diaries_source_text_encrypted_only " + definition);
            assertThat(migration.status().finalized()).isFalse();
            assertThatThrownBy(migration::requireReady).isInstanceOf(DiarySourceTextMigrationException.class);
            assertThatThrownBy(() -> migration.finalizeMigration(1))
                    .isInstanceOf(DiarySourceTextMigrationException.class);
            execute("ALTER TABLE diaries DROP CONSTRAINT ck_diaries_source_text_encrypted_only");
        }
    }

    @Test
    @DisplayName("다른 세션이 전환 실행 잠금을 보유하면 즉시 거절하고 호출자 연결은 유지한다")
    void rejectConcurrentMigrationExecutionWithoutClosingCallerConnection() throws SQLException {
        insertLegacy("중복 실행 방지", "SUCCEEDED", false);
        try (Connection owner = openConnection()) {
            scalar(owner, "SELECT pg_advisory_lock(?)", DiarySourceTextMigration.EXECUTION_LOCK_KEY);
            assertThatThrownBy(() -> migration.migrate(10, 1))
                    .isInstanceOf(DiarySourceTextMigrationException.class).hasMessage(FAILURE_MESSAGE).hasNoCause();
            assertThatThrownBy(() -> migration.verify(10)).isInstanceOf(DiarySourceTextMigrationException.class);
            assertThatThrownBy(() -> migration.finalizeMigration(10))
                    .isInstanceOf(DiarySourceTextMigrationException.class);
            assertConnectionRetained();
            scalar(owner, "SELECT pg_advisory_unlock(?)", DiarySourceTextMigration.EXECUTION_LOCK_KEY);
        }
        assertThat(migration.migrate(10, 1).migrated()).isEqualTo(1);
    }

    @Test
    @DisplayName("생성 재조회가 generation과 diary를 잠근 동안 배치는 대기하고 커밋 후 함께 전환한다")
    void waitForReplayLockOrderBeforeUpdatingEitherRepresentation() throws Exception {
        Fixture fixture = insertLegacy("재조회와 동시에 전환", "SUCCEEDED", false);
        Stored before = stored(fixture.diaryId());
        connection.setAutoCommit(false);
        scalar("SELECT id FROM diary_generations WHERE diary_id = ? FOR UPDATE", fixture.diaryId());
        scalar("SELECT id FROM diaries WHERE id = ? FOR UPDATE", fixture.diaryId());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Connection migrationConnection = openConnection();
        try {
            int pid = ((Number) scalar(migrationConnection, "SELECT pg_backend_pid()")).intValue();
            Future<DiarySourceTextMigration.MigrationResult> pending = worker.submit(() ->
                    new DiarySourceTextMigration(migrationConnection, cipher, fingerprints).migrate(10, 1));
            awaitDatabaseLock(pid);
            assertThat(stored(fixture.diaryId())).isEqualTo(before);
            connection.commit();
            connection.setAutoCommit(true);

            assertThat(pending.get(10, TimeUnit.SECONDS).migrated()).isEqualTo(1);
            assertEncrypted(fixture, cipher);
        } finally {
            releaseTransaction();
            worker.shutdownNow();
            worker.awaitTermination(10, TimeUnit.SECONDS);
            migrationConnection.close();
        }
    }

    @Test
    @DisplayName("처리 중 생성의 완료 트랜잭션을 기다린 뒤 잠근 최신 상태로 전환 여부를 판단한다")
    void recheckProcessingStatusAfterWaitingForCompletionTransaction() throws Exception {
        Fixture fixture = insertLegacy("대기 중 완료되는 생성", "PROCESSING", false);
        connection.setAutoCommit(false);
        scalar("SELECT id FROM diary_generations WHERE diary_id = ? FOR UPDATE", fixture.diaryId());
        execute("""
                UPDATE diary_generations SET status = 'SUCCEEDED', image_object_key = ?, completed_at = ?
                WHERE diary_id = ?
                """, "generated/%s.png".formatted(fixture.diaryId()), CREATED_AT, fixture.diaryId());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Connection migrationConnection = openConnection();
        try {
            int pid = ((Number) scalar(migrationConnection, "SELECT pg_backend_pid()")).intValue();
            Future<DiarySourceTextMigration.MigrationResult> pending = worker.submit(() ->
                    new DiarySourceTextMigration(migrationConnection, cipher, fingerprints).migrate(10, 1));
            awaitDatabaseLock(pid);
            connection.commit();
            connection.setAutoCommit(true);

            var result = pending.get(10, TimeUnit.SECONDS);
            assertThat(result.migrated()).isEqualTo(1);
            assertThat(result.skippedProcessing()).isZero();
            assertEncrypted(fixture, cipher);
        } finally {
            releaseTransaction();
            worker.shutdownNow();
            worker.awaitTermination(10, TimeUnit.SECONDS);
            migrationConnection.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"diaries", "diary_generations"})
    @DisplayName("양쪽 테이블의 RLS는 숨은 손상 원문이나 PROCESSING 생성을 성공·orphan으로 오인하지 않는다")
    void refuseRowSecurityOnEitherMigrationTable(String hiddenTable) throws Exception {
        boolean hiddenGeneration = "diary_generations".equals(hiddenTable);
        Fixture fixture = insertLegacy("RLS 아래의 원문", hiddenGeneration ? "PROCESSING" : "SUCCEEDED", false);
        if (!hiddenGeneration) {
            migration.migrate(1, 1);
            execute("UPDATE diaries SET source_text = ? WHERE id = ?",
                    cipher.encrypt(UUID.randomUUID(), USER_ID, fixture.source()), fixture.diaryId());
        }
        Stored before = stored(fixture.diaryId());
        String role = "migration_rls_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE ROLE " + role);
        execute("GRANT SELECT, UPDATE ON diaries, diary_generations TO " + role);
        execute("ALTER TABLE " + hiddenTable + " ENABLE ROW LEVEL SECURITY");
        execute("ALTER TABLE " + hiddenTable + " FORCE ROW LEVEL SECURITY");
        execute("CREATE POLICY hide_migration_rows ON " + hiddenTable + " USING (false)");
        try (Connection restricted = openConnection()) {
            executeOn(restricted, "SET ROLE " + role);
            assertThat(scalar(restricted, "SELECT COUNT(*) FROM public." + hiddenTable)).isEqualTo(0L);
            var hidden = new DiarySourceTextMigration(restricted, cipher, fingerprints);

            assertThatThrownBy(() -> hidden.migrate(1, 1)).isInstanceOf(DiarySourceTextMigrationException.class);
            assertThatThrownBy(() -> hidden.verify(1)).isInstanceOf(DiarySourceTextMigrationException.class);
            assertThatThrownBy(() -> hidden.finalizeMigration(1))
                    .isInstanceOf(DiarySourceTextMigrationException.class);
            assertThatThrownBy(hidden::requireReady).isInstanceOf(DiarySourceTextMigrationException.class);
        } finally {
            execute("REVOKE SELECT, UPDATE ON diaries, diary_generations FROM " + role);
            execute("DROP ROLE " + role);
        }
        assertThat(stored(fixture.diaryId())).isEqualTo(before);
    }

    @Test
    @DisplayName("복원 DB에서 한 일기에 생성 기록이 둘이면 한쪽 SHA만 바꾸거나 keyset에서 누락하지 않는다")
    void rejectDuplicateGenerationsBeforeChangingEitherRepresentation() throws SQLException {
        Fixture fixture = insertLegacy("중복 생성 기록의 원문", "SUCCEEDED", false);
        execute("ALTER TABLE diary_generations DROP CONSTRAINT uq_diary_generations_diary");
        execute("""
                INSERT INTO diary_generations (id, diary_id, prompt_id, idempotency_key, request_fingerprint,
                    status, title, image_object_key, completed_at)
                SELECT ?, diary_id, prompt_id, ?, request_fingerprint,
                    status, title, 'generated/duplicate.png', completed_at
                FROM diary_generations WHERE id = ?
                """, UUID.randomUUID(), UUID.randomUUID(), fixture.generationId());
        Stored before = stored(fixture.diaryId());

        assertThatThrownBy(() -> migration.migrate(1, 10)).isInstanceOf(DiarySourceTextMigrationException.class);
        assertThatThrownBy(() -> migration.verify(1)).isInstanceOf(DiarySourceTextMigrationException.class);
        assertThatThrownBy(() -> migration.finalizeMigration(1)).isInstanceOf(DiarySourceTextMigrationException.class);

        assertThat(stored(fixture.diaryId())).isEqualTo(before);
        assertThat(scalar("SELECT COUNT(*) FROM diary_generations WHERE request_fingerprint = ?",
                rawFingerprint(fixture))).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("PK 없는 복원 DB의 101개 중복 UUID와 null ID는 keyset 누락 전에 거절한다")
    void rejectDuplicateOrNullCursorIdsBeforePaginating(boolean nullable) throws SQLException {
        execute("ALTER TABLE diaries DROP CONSTRAINT pk_diaries CASCADE");
        if (nullable) {
            execute("ALTER TABLE diaries ALTER COLUMN id DROP NOT NULL");
        }
        UUID id = UUID.randomUUID();
        String encrypted = cipher.encrypt(id, USER_ID, "복원 DB 중복 ID");
        for (int index = 0; index < 101; index++) {
            execute("""
                    INSERT INTO diaries (id, user_id, diary_date, source_text, source_text_version)
                    VALUES (?, ?, ?, ?, 1)
                    """, id, USER_ID, DIARY_DATE, encrypted);
        }
        if (nullable) {
            execute("""
                    INSERT INTO diaries (id, user_id, diary_date, source_text, source_text_version)
                    VALUES (NULL, ?, ?, ?, 1)
                    """, USER_ID, DIARY_DATE, encrypted);
        }

        assertThatThrownBy(() -> migration.verify(1)).isInstanceOf(DiarySourceTextMigrationException.class);
        assertThatThrownBy(() -> migration.finalizeMigration(1)).isInstanceOf(DiarySourceTextMigrationException.class);
        assertThatThrownBy(migration::status).isInstanceOf(DiarySourceTextMigrationException.class);
        assertThatThrownBy(migration::requireReady).isInstanceOf(DiarySourceTextMigrationException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("실제 커밋 뒤 응답이 유실되면 전환과 완료 모두 성공 결과 없이 중단하고 연결을 폐기한다")
    void abortTheWholeOperationWhenCommitAcknowledgementIsLost(boolean finalizing) throws Exception {
        Fixture first = insertLegacy("커밋 응답 유실 첫 행", "SUCCEEDED", false);
        Fixture second = insertLegacy("계속 처리하면 안 되는 다음 행", "SUCCEEDED", false);
        if (finalizing) {
            migration.migrate(10, 1);
        }
        AtomicInteger commits = new AtomicInteger();
        Connection original = connection;
        Connection uncertain = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if ("commit".equals(method.getName()) && commits.incrementAndGet() == 2) {
                        original.commit();
                        throw new SQLException("private-commit-response-lost");
                    }
                    try {
                        return method.invoke(original, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );
        var interrupted = new DiarySourceTextMigration(uncertain, cipher, fingerprints);

        assertThatThrownBy(() -> {
            if (finalizing) {
                interrupted.finalizeMigration(10);
            } else {
                interrupted.migrate(10, 1);
            }
        }).isExactlyInstanceOf(DiarySourceTextMigrationException.class).hasMessage(FAILURE_MESSAGE).hasNoCause();

        assertThat(original.isClosed()).isTrue();
        assertThat(commits).hasValue(2);
        connection = openConnection();
        assertEncrypted(first, cipher);
        if (finalizing) {
            new DiarySourceTextMigration(connection, cipher, fingerprints).requireReady();
        } else {
            assertThat(stored(second.diaryId()).version()).isZero();
            assertThat(stored(second.diaryId()).fingerprint()).isEqualTo(rawFingerprint(second));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"isClosed", "setAutoCommit"})
    @DisplayName("행 커밋 뒤 연결 상태 확인이나 복원이 실패하면 다음 행을 처리하지 않는다")
    void abortBeforeTheNextRowWhenConnectionRestorationFails(String failingMethod) throws Exception {
        Fixture first = insertLegacy("상태 복원 실패 첫 행", "SUCCEEDED", false);
        Fixture second = insertLegacy("상태가 불명확하면 건드리지 않을 행", "SUCCEEDED", false);
        Connection original = connection;
        AtomicInteger commits = new AtomicInteger();
        Connection uncertain = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if (failingMethod.equals(method.getName()) && commits.get() == 2
                            && (!"setAutoCommit".equals(failingMethod) || Boolean.TRUE.equals(arguments[0]))) {
                        throw new SQLException("private-connection-restoration-failed");
                    }
                    try {
                        Object result = method.invoke(original, arguments);
                        if ("commit".equals(method.getName())) {
                            commits.incrementAndGet();
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );

        assertThatThrownBy(() -> new DiarySourceTextMigration(uncertain, cipher, fingerprints).migrate(10, 1))
                .isExactlyInstanceOf(DiarySourceTextMigrationException.class).hasMessage(FAILURE_MESSAGE).hasNoCause();

        assertThat(original.isClosed()).isTrue();
        assertThat(commits).hasValue(2);
        connection = openConnection();
        assertEncrypted(first, cipher);
        assertThat(stored(second.diaryId()).version()).isZero();
        assertThat(stored(second.diaryId()).fingerprint()).isEqualTo(rawFingerprint(second));
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "verify", "migrate", "finalize", "ready"})
    @DisplayName("스키마 조회 트랜잭션의 커밋 응답 유실도 작업 전체를 실패시키고 연결을 폐기한다")
    void abortWhenAnInspectionTransactionLosesCommitAcknowledgement(String operation) throws Exception {
        insertEncryptedOrphan("읽기 검사 커밋 응답 유실");
        migration.finalizeMigration(1);
        Connection original = connection;
        AtomicInteger commits = new AtomicInteger();
        Connection uncertain = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if ("commit".equals(method.getName())) {
                        commits.incrementAndGet();
                        original.commit();
                        throw new SQLException("private-inspection-commit-response-lost");
                    }
                    try {
                        return method.invoke(original, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );
        var interrupted = new DiarySourceTextMigration(uncertain, cipher, fingerprints);

        assertThatThrownBy(() -> {
            switch (operation) {
                case "status" -> interrupted.status();
                case "verify" -> interrupted.verify(1);
                case "migrate" -> interrupted.migrate(1, 1);
                case "finalize" -> interrupted.finalizeMigration(1);
                case "ready" -> interrupted.requireReady();
                default -> throw new AssertionError("Unknown test operation.");
            }
        }).isExactlyInstanceOf(DiarySourceTextMigrationException.class).hasMessage(FAILURE_MESSAGE).hasNoCause();

        assertThat(original.isClosed()).isTrue();
        assertThat(commits).hasValue(1);
        connection = openConnection();
        assertThat(new DiarySourceTextMigration(connection, cipher, fingerprints).verify(1).failed()).isZero();
    }

    @Test
    @DisplayName("전수 검증 중에는 두 테이블의 DDL을 막아 검사한 스키마가 중간에 바뀌지 않는다")
    void retainSchemaLocksUntilVerificationCompletes() throws Exception {
        Fixture fixture = insertLegacy("DDL 경합 검증", "SUCCEEDED", false);
        migration.migrate(1, 1);
        CountDownLatch verifying = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        DiarySourceTextCipher paused = new DiarySourceTextCipher() {
            @Override
            public String encrypt(UUID diaryId, UUID userId, String text) {
                return cipher.encrypt(diaryId, userId, text);
            }

            @Override
            public String decrypt(UUID diaryId, UUID userId, String storedText, int version) {
                verifying.countDown();
                try {
                    if (!finish.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Verification release timed out.");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return cipher.decrypt(diaryId, userId, storedText, version);
            }

            @Override
            public String protectRequestFingerprint(UUID diaryId, UUID userId, String text, int version, String raw) {
                return cipher.protectRequestFingerprint(diaryId, userId, text, version, raw);
            }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection verifier = openConnection(); Connection ddl = openConnection()) {
            Future<DiarySourceTextMigration.VerificationResult> pending = worker.submit(() ->
                    new DiarySourceTextMigration(verifier, paused, fingerprints).verify(1));
            try {
                assertThat(verifying.await(5, TimeUnit.SECONDS)).isTrue();
                executeOn(ddl, "SET lock_timeout = '100ms'");
                assertThatThrownBy(() -> executeOn(ddl,
                        "ALTER TABLE public.diary_generations DROP CONSTRAINT uq_diary_generations_diary"))
                        .isInstanceOf(SQLException.class);
            } finally {
                finish.countDown();
            }
            assertThat(pending.get(10, TimeUnit.SECONDS).failed()).isZero();
            assertEncrypted(fixture, cipher);
        } finally {
            finish.countDown();
            worker.shutdownNow();
            worker.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private void executeOn(Connection selected, String sql) throws SQLException {
        try (PreparedStatement statement = selected.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private Fixture insertLegacy(String source, String status, boolean deleted) throws SQLException {
        UUID diaryId = UUID.fromString("00000000-0000-0000-0000-%012d".formatted(nextDiaryId++));
        UUID generationId = status == null ? null : UUID.randomUUID();
        Fixture fixture = new Fixture(diaryId, generationId, UUID.randomUUID(), source);
        execute("""
                INSERT INTO diaries (id, user_id, diary_date, source_text, source_text_version,
                    created_at, updated_at, deleted_at)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?)
                """, diaryId, USER_ID, DIARY_DATE, source, CREATED_AT, CREATED_AT, deleted ? CREATED_AT : null);
        if (status != null) {
            execute("""
                    INSERT INTO diary_generations (id, diary_id, prompt_id, idempotency_key,
                        request_fingerprint, status, title, image_object_key, error_code,
                        created_at, updated_at, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, generationId, diaryId, promptId, fixture.idempotencyKey(), rawFingerprint(fixture), status,
                    "기존 제목", "SUCCEEDED".equals(status) ? "generated/%s.png".formatted(diaryId) : null,
                    "FAILED".equals(status) ? "AI_PROVIDER_ERROR" : null, CREATED_AT, CREATED_AT,
                    "PROCESSING".equals(status) ? null : CREATED_AT);
        }
        return fixture;
    }

    private void insertEncryptedOrphan(String source) throws SQLException {
        UUID diaryId = UUID.randomUUID();
        execute("""
                INSERT INTO diaries (id, user_id, diary_date, source_text, source_text_version)
                VALUES (?, ?, ?, ?, 1)
                """, diaryId, USER_ID, DIARY_DATE, cipher.encrypt(diaryId, USER_ID, source));
    }

    private String rawFingerprint(Fixture fixture) {
        return fingerprints.generate(new GenerateDiaryImageCommand(
                USER_ID, fixture.diaryId(), DIARY_DATE, fixture.source(), fixture.idempotencyKey()
        ));
    }

    private Stored stored(UUID diaryId) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                SELECT d.source_text, d.source_text_version, g.request_fingerprint
                FROM diaries d LEFT JOIN diary_generations g ON g.diary_id = d.id WHERE d.id = ?
                """, diaryId); ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return new Stored(rows.getString(1), rows.getInt(2), rows.getString(3));
        }
    }

    private List<Object> businessState(UUID diaryId) throws SQLException {
        try (PreparedStatement statement = prepare(connection, """
                SELECT d.created_at, d.updated_at, d.deleted_at, g.status, g.title, g.image_object_key,
                    g.error_code, g.created_at, g.updated_at, g.completed_at, g.usage_date
                FROM diaries d JOIN diary_generations g ON g.diary_id = d.id WHERE d.id = ?
                """, diaryId); ResultSet rows = statement.executeQuery()) {
            rows.next();
            List<Object> result = new ArrayList<>();
            for (int index = 1; index <= 11; index++) {
                result.add(rows.getObject(index));
            }
            return result;
        }
    }

    private void assertEncrypted(Fixture fixture, DiarySourceTextCipher selectedCipher) throws SQLException {
        Stored stored = stored(fixture.diaryId());
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.source()).isNotEqualTo(fixture.source());
        assertThat(selectedCipher.decrypt(fixture.diaryId(), USER_ID, stored.source(), 1))
                .isEqualTo(fixture.source());
        if (fixture.generationId() != null) {
            assertThat(stored.fingerprint()).isNotEqualTo(rawFingerprint(fixture))
                    .isEqualTo(selectedCipher.protectRequestFingerprint(
                            fixture.diaryId(), USER_ID, stored.source(), 1, rawFingerprint(fixture)
                    ));
        }
    }

    private Object versionDefault() throws SQLException {
        return scalar("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'diaries' AND column_name = 'source_text_version'
                """);
    }

    private void assertConnectionRetained() throws SQLException {
        assertThat(connection.isClosed()).isFalse();
        assertThat(connection.getAutoCommit()).isTrue();
        assertThat(scalar("SELECT 1")).isEqualTo(1);
    }

    private void awaitDatabaseLock(int pid) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            scalar("SELECT pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(scalar("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock')
                    """, pid))) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("Migration did not wait for the actual PostgreSQL row lock.");
    }

    private void releaseTransaction() throws SQLException {
        if (!connection.getAutoCommit()) {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, parameters)) {
            statement.executeUpdate();
        }
    }

    private Object scalar(String sql, Object... parameters) throws SQLException {
        return scalar(connection, sql, parameters);
    }

    private Object scalar(Connection selectedConnection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(selectedConnection, sql, parameters);
                ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getObject(1);
        }
    }

    private PreparedStatement prepare(Connection selectedConnection, String sql, Object... parameters)
            throws SQLException {
        PreparedStatement statement = selectedConnection.prepareStatement(sql);
        for (int index = 0; index < parameters.length; index++) {
            Object value = parameters[index];
            statement.setObject(index + 1, value instanceof Instant instant ? java.sql.Timestamp.from(instant) : value);
        }
        return statement;
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static DiarySourceTextCipher cipher(String activeKeyId, Map<String, String> keys) {
        return new AesGcmDiarySourceTextCipher(new DiarySourceTextEncryptionProperties(activeKeyId, keys));
    }

    private static String key(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private record Fixture(UUID diaryId, UUID generationId, UUID idempotencyKey, String source) {
    }

    private record Stored(String source, int version, String fingerprint) {
    }
}
