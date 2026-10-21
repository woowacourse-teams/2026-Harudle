package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DiarySourceTextKeyInitializationTest {

    private static final UUID USER_ID = UUID.fromString("22817ac8-896a-4b0b-83d0-678457377032");
    private static final UUID DIARY_ID = UUID.fromString("b99c9868-9bd6-40fa-8932-06b23527bfdc");
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String FAILURE_MESSAGE = "일기 원문 암호화 처리에 실패했습니다.";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @TempDir
    private Path directory;

    private final DiarySourceTextEncryptionProperties properties =
            new DiarySourceTextEncryptionProperties("primary", Map.of("primary", KEY));
    private final AesGcmDiarySourceTextCipher cipher = new AesGcmDiarySourceTextCipher(properties);
    private final DiarySourceTextKeyVerification verification = new DiarySourceTextKeyVerification(properties);
    private final DiarySourceTextKeyInitialization initialization = new DiarySourceTextKeyInitialization(verification);
    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        resetSchema("28");
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("빈 V28 DB와 v0만 있는 DB는 최초 자료를 만들고 연결 및 기존 행을 보존한다")
    void initializeOnlyWhenTheKnownSchemaHasNoEncryptedData(boolean legacyExists) throws Exception {
        if (legacyExists) {
            insertDiary("기존 평문 일기", 0, false);
        }

        initialization.initialize(connection, file(), "dev");

        verification.verify(file(), "dev");
        assertThat(Files.getPosixFilePermissions(file())).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertConnectionRetained(connection);
        assertThat(scalar(connection, "SELECT COUNT(*) FROM diaries")).isEqualTo(legacyExists ? 1L : 0L);
        if (legacyExists) {
            assertThat(scalar(connection, "SELECT source_text FROM diaries WHERE id = ?", DIARY_ID))
                    .isEqualTo("기존 평문 일기");
            assertThat(scalar(connection, "SELECT source_text_version FROM diaries WHERE id = ?", DIARY_ID))
                    .isEqualTo(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("생성 기록이 없거나 삭제된 v1도 정상 키와 잘못된 키 모두 재초기화를 거절한다")
    void refuseInitializationForEveryEncryptedDiaryEvenWithTheCorrectKey(boolean deleted) throws Exception {
        String encrypted = cipher.encrypt(DIARY_ID, USER_ID, "잃어버리면 안 되는 기존 원문");
        insertDiary(encrypted, 1, deleted);

        assertCryptoFailure(() -> initialization.initialize(connection, file(), "dev"));
        byte[] wrongKey = new byte[32];
        wrongKey[0] = 1;
        var wrong = new DiarySourceTextKeyVerification(new DiarySourceTextEncryptionProperties(
                "primary", Map.of("primary", Base64.getEncoder().encodeToString(wrongKey))
        ));
        assertCryptoFailure(() -> new DiarySourceTextKeyInitialization(wrong).initialize(connection, file(), "dev"));

        assertThat(file()).doesNotExist();
        assertThat(scalar(connection, "SELECT source_text FROM diaries WHERE id = ?", DIARY_ID)).isEqualTo(encrypted);
        assertThat(scalar(connection, "SELECT source_text_version FROM diaries WHERE id = ?", DIARY_ID)).isEqualTo(1);
        assertThat(scalar(connection, "SELECT COUNT(*) FROM diary_generations")).isEqualTo(0L);
        assertConnectionRetained(connection);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "27"})
    @DisplayName("실제 Flyway 이력이 있는 V28 이전의 알려진 평문 스키마도 최초 준비할 수 있다")
    void acceptKnownPreEncryptionSchemaWithSuccessfulFlywayHistory(String target) throws Exception {
        resetSchema(target);
        execute(connection, "INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, ?, ?)",
                DIARY_ID, USER_ID, LocalDate.of(2026, 10, 21), "V28 적용 전 평문");

        initialization.initialize(connection, file(), "dev");

        verification.verify(file(), "dev");
        assertThat(scalar(connection, "SELECT source_text FROM diaries WHERE id = ?", DIARY_ID))
                .isEqualTo("V28 적용 전 평문");
        assertConnectionRetained(connection);
    }

    @ParameterizedTest
    @ValueSource(strings = {"28", "999", "unknown"})
    @DisplayName("평문 모양이어도 V28 이상 또는 알 수 없는 이력이 있으면 초기화를 거절한다")
    void rejectPreEncryptionShapeWhoseHistoryCannotProveItsOrigin(String version) throws SQLException {
        resetSchema("27");
        execute(connection, "UPDATE flyway_schema_history SET version = ? WHERE version = '27'", version);

        assertCryptoFailure(() -> initialization.initialize(connection, file(), "dev"));

        assertThat(file()).doesNotExist();
        assertConnectionRetained(connection);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TYPE TEXT", "TYPE VARCHAR(301)", "DROP NOT NULL"})
    @DisplayName("과거 이력이 있어도 알려진 VARCHAR 300 NOT NULL 원문 스키마와 다르면 거절한다")
    void rejectUnknownLegacyColumnShapes(String alteration) throws SQLException {
        resetSchema("1");
        execute(connection, "ALTER TABLE diaries ALTER COLUMN source_text " + alteration);

        assertCryptoFailure(() -> initialization.initialize(connection, file(), "dev"));

        assertThat(file()).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"diaries", "flyway_schema_history"})
    @DisplayName("테이블 또는 마이그레이션 이력이 없으면 새 DB로 추측하지 않고 초기화를 거절한다")
    void rejectMissingTablesInsteadOfTreatingThemAsAnEmptyDatabase(String table) throws SQLException {
        execute(connection, "DROP TABLE " + table + " CASCADE");

        assertCryptoFailure(() -> initialization.initialize(connection, file(), "dev"));

        assertThat(file()).doesNotExist();
        assertConnectionRetained(connection);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "NULL"})
    @DisplayName("지원하지 않거나 null인 저장 버전은 암호문 부재의 근거가 되지 않는다")
    void rejectUnknownOrNullStorageVersions(String version) throws SQLException {
        insertDiary("확인할 수 없는 원문", 0, false);
        execute(connection, "ALTER TABLE diaries DROP CONSTRAINT ck_diaries_source_text_version");
        if ("NULL".equals(version)) {
            execute(connection, "ALTER TABLE diaries ALTER COLUMN source_text_version DROP NOT NULL");
        }
        execute(connection, "UPDATE diaries SET source_text_version = ? WHERE id = ?",
                "NULL".equals(version) ? null : Integer.valueOf(version), DIARY_ID);

        assertCryptoFailure(() -> initialization.initialize(connection, file(), "dev"));

        assertThat(file()).doesNotExist();
    }

    @Test
    @DisplayName("조회 권한 부족은 빈 DB로 처리하지 않고 원인을 숨긴 채 초기화를 거절한다")
    void refuseInitializationWhenDatabasePermissionsDoNotAllowInspection() throws SQLException {
        String role = "key_initialization_" + UUID.randomUUID().toString().replace("-", "");
        execute(connection, "CREATE ROLE " + role);
        try (Connection restricted = openConnection()) {
            execute(restricted, "SET ROLE " + role);
            assertCryptoFailure(() -> initialization.initialize(restricted, file(), "dev"));
            assertConnectionRetained(restricted);
        } finally {
            execute(connection, "DROP ROLE " + role);
        }
        assertThat(file()).doesNotExist();
    }

    @Test
    @DisplayName("닫힌 연결의 DB 오류도 원인과 접속 정보를 노출하거나 파일을 만들지 않는다")
    void refuseInitializationWhenTheConnectionIsUnavailable() throws SQLException {
        connection.close();

        assertCryptoFailure(() -> initialization.initialize(connection, file(), "dev"));

        assertThat(file()).doesNotExist();
    }

    @Test
    @DisplayName("기존 자료가 있으면 DB가 비어 있어도 파일과 수정 시각을 유지한다")
    void neverOverwriteExistingEvidence() throws Exception {
        verification.createInitialEvidence(file(), "dev");
        byte[] original = Files.readAllBytes(file());
        var modified = Files.getLastModifiedTime(file());

        assertCryptoFailure(() -> initialization.initialize(connection, file(), "dev"));

        assertThat(Files.readAllBytes(file())).isEqualTo(original);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(modified);
        assertConnectionRetained(connection);
    }

    @Test
    @DisplayName("실제 DB 커밋 후 응답이 유실되어도 초기 검증 파일은 공개하지 않고 연결을 폐기한다")
    void neverPublishEvidenceWhenCommitAcknowledgementIsUncertain() throws Exception {
        Connection uncertain = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if ("commit".equals(method.getName())) {
                        connection.commit();
                        throw new SQLException("private-commit-acknowledgement-lost");
                    }
                    try {
                        return method.invoke(connection, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );

        assertCryptoFailure(() -> initialization.initialize(uncertain, file(), "dev"));

        assertThat(file()).doesNotExist();
        assertThat(connection.isClosed()).isTrue();
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("keys.json.lock");
        }
    }

    @Test
    @DisplayName("미커밋 v1 INSERT와 충돌하면 실제 SHARE 잠금으로 기다린 뒤 커밋한 암호문을 보고 거절한다")
    void waitForAnInFlightEncryptedWriteAndRejectAfterItCommits() throws Exception {
        connection.setAutoCommit(false);
        insertDiary(cipher.encrypt(DIARY_ID, USER_ID, "초기화와 동시에 생성한 원문"), 1, false);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Connection initializerConnection = openConnection();
        try {
            int pid = ((Number) scalar(initializerConnection, "SELECT pg_backend_pid()")).intValue();
            Future<Throwable> pending = worker.submit(() -> initializeFailure(initializerConnection));
            awaitDatabaseLock(pid);
            assertThat(file()).doesNotExist();
            connection.commit();
            connection.setAutoCommit(true);

            assertFixedFailure(pending.get(10, TimeUnit.SECONDS));
            assertThat(file()).doesNotExist();
            assertConnectionRetained(initializerConnection);
        } finally {
            releaseTransaction();
            worker.shutdownNow();
            worker.awaitTermination(10, TimeUnit.SECONDS);
            initializerConnection.close();
        }
    }

    @Test
    @DisplayName("잠금 제한 시간이 지나면 초기화를 거절하고 이후 재시도에서만 자료를 만든다")
    void failClosedOnLockTimeoutAndAllowAnExplicitRetry() throws Exception {
        connection.setAutoCommit(false);
        execute(connection, "LOCK TABLE diaries IN ACCESS EXCLUSIVE MODE");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Connection initializerConnection = openConnection();
        try {
            Future<Throwable> pending = worker.submit(() -> initializeFailure(initializerConnection));

            assertFixedFailure(pending.get(10, TimeUnit.SECONDS));
            assertThat(file()).doesNotExist();
            assertConnectionRetained(initializerConnection);
            connection.commit();
            connection.setAutoCommit(true);
            initialization.initialize(initializerConnection, file(), "dev");
            verification.verify(file(), "dev");
        } finally {
            releaseTransaction();
            worker.shutdownNow();
            worker.awaitTermination(10, TimeUnit.SECONDS);
            initializerConnection.close();
        }
    }

    @Test
    @DisplayName("복원한 키링은 100개를 넘는 구키·신키 v1을 모두 인증하고 v0는 건너뛴다")
    void verifyStoredDataAcrossPagesAndKeyRotationWithoutChangingRows() throws SQLException {
        byte[] newKey = new byte[32];
        newKey[0] = 7;
        var rotatedCipher = new AesGcmDiarySourceTextCipher(new DiarySourceTextEncryptionProperties(
                "next", Map.of("primary", KEY, "next", Base64.getEncoder().encodeToString(newKey))
        ));
        for (int index = 1; index <= 105; index++) {
            UUID diaryId = new UUID(0, index);
            var writer = index <= 53 ? cipher : rotatedCipher;
            insertStoredDiary(diaryId, writer.encrypt(diaryId, USER_ID, "복원 검증 " + index), 1, false);
        }
        insertDiary("변환 전 평문은 복호화 대상으로 세지 않는다", 0, false);
        int originalIsolation = connection.getTransactionIsolation();

        var result = new DiarySourceTextStoredDataVerification(rotatedCipher).verify(connection);

        assertThat(result.checked()).isEqualTo(105);
        assertThat(result.failed()).isZero();
        assertThat(scalar(connection, "SELECT COUNT(*) FROM diaries")).isEqualTo(106L);
        assertThat(scalar(connection, "SELECT source_text FROM diaries WHERE id = ?", DIARY_ID))
                .isEqualTo("변환 전 평문은 복호화 대상으로 세지 않는다");
        assertThat(connection.isReadOnly()).isFalse();
        assertThat(connection.getTransactionIsolation()).isEqualTo(originalIsolation);
        assertConnectionRetained(connection);
    }

    @Test
    @DisplayName("실제 데이터 검증은 삭제·생성 orphan도 포함하여 변조·소유자 불일치·키 누락을 행별 집계한다")
    void countDamagedWrongOwnerAndMissingKeyRowsWithoutExposingTheirContents() throws SQLException {
        UUID validId = new UUID(0, 1);
        insertStoredDiary(validId, cipher.encrypt(validId, USER_ID, "정상 복원 원문"), 1, false);
        insertStoredDiary(new UUID(0, 2), cipher.encrypt(UUID.randomUUID(), USER_ID, "다른 일기의 암호문"), 1, true);
        UUID wrongOwnerId = new UUID(0, 3);
        insertStoredDiary(wrongOwnerId, cipher.encrypt(wrongOwnerId, UUID.randomUUID(), "다른 소유자의 암호문"), 1, false);
        byte[] missingKey = new byte[32];
        missingKey[0] = 9;
        var missingKeyCipher = new AesGcmDiarySourceTextCipher(new DiarySourceTextEncryptionProperties(
                "removed", Map.of("removed", Base64.getEncoder().encodeToString(missingKey))
        ));
        UUID missingKeyId = new UUID(0, 4);
        insertStoredDiary(missingKeyId, missingKeyCipher.encrypt(missingKeyId, USER_ID, "누락된 키의 암호문"), 1, false);

        var result = new DiarySourceTextStoredDataVerification(cipher).verify(connection);

        assertThat(result.checked()).isEqualTo(4);
        assertThat(result.failed()).isEqualTo(3);
        assertThat(scalar(connection, "SELECT COUNT(*) FROM diary_generations")).isEqualTo(0L);
        assertThat(scalar(connection, "SELECT COUNT(*) FROM diaries WHERE deleted_at IS NOT NULL")).isEqualTo(1L);
        assertConnectionRetained(connection);
    }

    @Test
    @DisplayName("RLS가 실제 v1 행을 숨겨도 검증은 0건 성공으로 처리하지 않고 스키마를 거절한다")
    void refuseDataVerificationWhenRowSecurityHidesEncryptedRows() throws SQLException {
        insertDiary(cipher.encrypt(DIARY_ID, USER_ID, "RLS로 숨긴 검증 대상"), 1, false);
        execute(connection, "ALTER TABLE diaries ENABLE ROW LEVEL SECURITY");
        execute(connection, "ALTER TABLE diaries FORCE ROW LEVEL SECURITY");
        execute(connection, "CREATE POLICY hide_key_check_rows ON diaries USING (false)");
        String role = "key_data_verification_" + UUID.randomUUID().toString().replace("-", "");
        execute(connection, "CREATE ROLE " + role);
        execute(connection, "GRANT SELECT ON diaries TO " + role);
        try (Connection restricted = openConnection()) {
            execute(restricted, "SET ROLE " + role);
            assertThat(scalar(restricted, "SELECT COUNT(*) FROM diaries")).isEqualTo(0L);

            assertCryptoFailure(() -> new DiarySourceTextStoredDataVerification(cipher).verify(restricted));
        } finally {
            execute(connection, "REVOKE SELECT ON diaries FROM " + role);
            execute(connection, "DROP ROLE " + role);
        }
        assertThat(scalar(connection, "SELECT COUNT(*) FROM diaries")).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "NULL"})
    @DisplayName("실제 데이터 검증도 미지원·null 버전을 성공 집계로 숨기지 않고 전체 작업을 거절한다")
    void refuseDataVerificationForUnknownStorageVersions(String version) throws SQLException {
        insertDiary("알 수 없는 버전", 0, false);
        execute(connection, "ALTER TABLE diaries DROP CONSTRAINT ck_diaries_source_text_version");
        if ("NULL".equals(version)) {
            execute(connection, "ALTER TABLE diaries ALTER COLUMN source_text_version DROP NOT NULL");
        }
        execute(connection, "UPDATE diaries SET source_text_version = ? WHERE id = ?",
                "NULL".equals(version) ? null : Integer.valueOf(version), DIARY_ID);

        assertCryptoFailure(() -> new DiarySourceTextStoredDataVerification(cipher).verify(connection));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("복원 스키마의 ID 기본키가 없으면 중복 101행과 null ID를 누락시키지 않고 검증을 거절한다")
    void refuseDataVerificationWithoutARequiredSingleIdPrimaryKey(boolean nullableId) throws SQLException {
        execute(connection, "ALTER TABLE diaries DROP CONSTRAINT pk_diaries CASCADE");
        if (nullableId) {
            execute(connection, "ALTER TABLE diaries ALTER COLUMN id DROP NOT NULL");
        }
        String encrypted = cipher.encrypt(DIARY_ID, USER_ID, "중복 ID를 가진 복원 행");
        for (int index = 0; index < 101; index++) {
            insertStoredDiary(DIARY_ID, encrypted, 1, false);
        }
        if (nullableId) {
            insertStoredDiary(null, encrypted, 1, false);
        }

        assertCryptoFailure(() -> new DiarySourceTextStoredDataVerification(cipher).verify(connection));
    }

    @Test
    @DisplayName("실제 데이터 검증 중 스키마 오류는 부분 성공 결과 대신 고정 오류로 거절한다")
    void refuseDataVerificationWhenTheVersionColumnIsMissing() throws SQLException {
        execute(connection, "ALTER TABLE diaries DROP COLUMN source_text_version");

        assertCryptoFailure(() -> new DiarySourceTextStoredDataVerification(cipher).verify(connection));
    }

    private Throwable initializeFailure(Connection selectedConnection) {
        try {
            initialization.initialize(selectedConnection, file(), "dev");
            return null;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private void resetSchema(String target) throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        // Recreate directly because a previous test may deliberately corrupt the Flyway history.
        try (Connection clean = openConnection()) {
            execute(clean, "DROP SCHEMA IF EXISTS public CASCADE");
            execute(clean, "CREATE SCHEMA public");
            execute(clean, "GRANT USAGE ON SCHEMA public TO PUBLIC");
        }
        Flyway.configure()
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(target).load().migrate();
        connection = openConnection();
        execute(connection, "INSERT INTO users (id, name) VALUES (?, ?)", USER_ID, "키 초기화 검증 사용자");
    }

    private void insertDiary(String source, int version, boolean deleted) throws SQLException {
        insertStoredDiary(DIARY_ID, source, version, deleted);
    }

    private void insertStoredDiary(UUID diaryId, String source, int version, boolean deleted) throws SQLException {
        execute(connection, """
                INSERT INTO diaries (id, user_id, diary_date, source_text, source_text_version, deleted_at)
                VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, diaryId, USER_ID, LocalDate.of(2026, 10, 21), source, version, deleted);
    }

    private Path file() {
        return directory.resolve("keys.json");
    }

    private void awaitDatabaseLock(int pid) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            scalar(connection, "SELECT pg_stat_clear_snapshot()");
            if (Boolean.TRUE.equals(scalar(connection, """
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock')
                    """, pid))) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("Key initialization did not wait for the PostgreSQL writer lock.");
    }

    private void releaseTransaction() throws SQLException {
        if (!connection.getAutoCommit()) {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private void assertConnectionRetained(Connection selectedConnection) throws SQLException {
        assertThat(selectedConnection.isClosed()).isFalse();
        assertThat(selectedConnection.getAutoCommit()).isTrue();
        assertThat(scalar(selectedConnection, "SELECT 1")).isEqualTo(1);
    }

    private static void assertCryptoFailure(ThrowingCallable operation) {
        assertThatThrownBy(operation).isExactlyInstanceOf(DiarySourceTextCryptoException.class)
                .hasMessage(FAILURE_MESSAGE).hasNoCause();
    }

    private static void assertFixedFailure(Throwable failure) {
        assertThat(failure).isExactlyInstanceOf(DiarySourceTextCryptoException.class)
                .hasMessage(FAILURE_MESSAGE).hasNoCause();
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void execute(Connection selectedConnection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepare(selectedConnection, sql, parameters)) {
            statement.executeUpdate();
        }
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
            statement.setObject(index + 1, parameters[index]);
        }
        return statement;
    }
}
