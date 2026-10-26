package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration;
import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration.MigrationResult;
import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration.Status;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class DiarySourceTextBackfillMainTest {

    private static final String PREFIX = "harudle.diary.source-text-encryption.";
    private static final String DATABASE_URL = "jdbc:harudle-cli-test:no-network";

    @TempDir
    private Path directory;

    @Test
    @DisplayName("CLI는 Error와 원인 및 정리 중 suppressed 진단을 출력하지 않고 실패 코드로 중단한다")
    void suppressesFatalErrorDiagnosticsAtCliBoundary(CapturedOutput output) throws SQLException {
        MockEnvironment environment = verifiedEnvironment();
        Connection connection = mock(Connection.class);
        AssertionError failure = new AssertionError("private-error-sentinel",
                new SQLException("private-cause-sentinel"));
        failure.addSuppressed(new SQLException("private-suppressed-sentinel"));
        SQLException closeFailure = new SQLException("private-close-sentinel");
        when(connection.getAutoCommit()).thenThrow(failure);
        doThrow(closeFailure).when(connection).close();

        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(any(String.class), any(Properties.class)))
                    .thenReturn(connection);
            assertFailure(output, new String[]{"status"}, environment, "status", "UNEXPECTED_FAILURE");

            driver.verify(() -> DriverManager.getConnection(any(String.class), any(Properties.class)));
        }

        verify(connection).getAutoCommit();
        verify(connection).close();
        assertThat(failure.getSuppressed()).contains(closeFailure);
        assertThat(output.getOut() + output.getErr()).doesNotContain("private-error-sentinel",
                "private-cause-sentinel", "private-suppressed-sentinel", "private-close-sentinel");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"private-command-sentinel", "private-\"command\n-sentinel"})
    @DisplayName("잘못된 명령은 입력을 되비추지 않고 invalid 명령과 고정 설정 오류 코드만 출력한다")
    void reportsInvalidCommandWithoutEchoingInput(String command, CapturedOutput output) {
        assertFailure(output, new String[]{command}, new TestEnvironment(), "invalid", "CONFIGURATION_INVALID");
    }

    @Test
    @DisplayName("누락된 명령과 잘못된 옵션은 설정 오류로 구분하고 알려진 명령 이름만 출력한다")
    void reportsMissingCommandAndInvalidOptions(CapturedOutput output) {
        assertFailure(output, new String[]{}, new TestEnvironment(), "invalid", "CONFIGURATION_INVALID");
        assertFailure(output, new String[]{"migrate", "--batch-size", "private-option-sentinel"},
                new TestEnvironment(), "migrate", "CONFIGURATION_INVALID");
    }

    @Test
    @DisplayName("잘못된 키 설정은 DB 연결 전에 고정 설정 오류 코드로 보고한다")
    void reportsConfigurationFailureBeforeConnecting(CapturedOutput output) {
        MockEnvironment environment = verifiedEnvironment()
                .withProperty(PREFIX + "keys.primary", "private-invalid-key-sentinel");
        try (var driver = mockStatic(DriverManager.class)) {
            assertFailure(output, new String[]{"status"}, environment, "status", "CONFIGURATION_INVALID");

            driver.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("검증 자료를 읽지 못하면 경로나 파일 내용을 출력하지 않고 키 검증 실패로 보고한다")
    void reportsKeyVerificationFailureBeforeConnecting(CapturedOutput output) {
        MockEnvironment environment = verifiedEnvironment().withProperty(PREFIX + "verification-file",
                directory.resolve("private-missing-canary-sentinel.json").toString());
        try (var driver = mockStatic(DriverManager.class)) {
            assertFailure(output, new String[]{"status"}, environment, "status", "KEY_VERIFICATION_FAILED");

            driver.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("접속 실패는 JDBC 진단과 원인을 출력하지 않고 DB 연결 실패로 구분한다")
    void reportsDatabaseConnectionFailureWithoutDriverDiagnostics(CapturedOutput output) {
        MockEnvironment environment = verifiedEnvironment();
        SQLException failure = new SQLException("private-connection-sentinel",
                new IllegalStateException("private-cause-sentinel"));
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(any(String.class), any(Properties.class)))
                    .thenThrow(failure);

            assertFailure(output, new String[]{"status"}, environment, "status", "DATABASE_CONNECTION_FAILED");
        }
    }

    @Test
    @DisplayName("접속 후 작업 실패는 SQL 진단을 출력하지 않고 DB 작업 실패로 구분한다")
    void reportsDatabaseOperationFailureWithoutSqlDiagnostics(CapturedOutput output) throws SQLException {
        MockEnvironment environment = verifiedEnvironment();
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenThrow(new SQLException("private-operation-sentinel"));
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(any(String.class), any(Properties.class)))
                    .thenReturn(connection);

            assertFailure(output, new String[]{"ready"}, environment, "ready", "DATABASE_OPERATION_FAILED");
        }
        verify(connection).close();
    }

    @Test
    @DisplayName("지원하지 않는 전환 격리 수준은 DB 작업 실패와 구분하며 잠금을 시작하지 않는다")
    void reportsUnsupportedIsolationBeforeLocking(CapturedOutput output) throws SQLException {
        MockEnvironment environment = verifiedEnvironment();
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(any(String.class), any(Properties.class)))
                    .thenReturn(connection);

            assertFailure(output, new String[]{"migrate"}, environment, "migrate", "ISOLATION_LEVEL_UNSUPPORTED");
        }
        verify(connection, never()).prepareStatement(any(String.class));
        verify(connection, never()).setAutoCommit(any(Boolean.class));
        verify(connection).close();
    }

    @Test
    @DisplayName("집계 완료 뒤 연결 종료가 실패해도 집계와 오류 JSON을 중복 출력하지 않는다")
    void reportsOnlyFailureWhenConnectionCloseFails(CapturedOutput output) throws SQLException {
        MockEnvironment environment = verifiedEnvironment();
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("private-close-sentinel")).when(connection).close();
        try (var migrations = mockConstruction(DiarySourceTextMigration.class, (migration, context) ->
                when(migration.status()).thenReturn(new Status(0, 1, 0, 0, 0, false)));
                var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(any(String.class), any(Properties.class)))
                    .thenReturn(connection);

            assertFailure(output, new String[]{"status"}, environment, "status", "DATABASE_OPERATION_FAILED");
            assertThat(migrations.constructed()).hasSize(1);
        }
    }

    @Test
    @DisplayName("행별 실패는 오류 JSON으로 바꾸지 않고 기존 집계와 종료 코드 1을 유지한다")
    void preservesAggregateResultForRowFailures(CapturedOutput output) {
        MockEnvironment environment = verifiedEnvironment();
        Connection connection = mock(Connection.class);
        Status remaining = new Status(1, 0, 0, 0, 0, false);
        try (var migrations = mockConstruction(DiarySourceTextMigration.class, (migration, context) ->
                when(migration.migrate(100, 1)).thenReturn(new MigrationResult(1, 0, 0, 1, 0, remaining)));
                var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(any(String.class), any(Properties.class)))
                    .thenReturn(connection);
            String before = output.getOut();
            String errorsBefore = output.getErr();

            assertThat(DiarySourceTextBackfillMain.execute(new String[]{"migrate"}, environment)).isEqualTo(1);

            assertThat(output.getOut()).isEqualTo(before + "{\"command\":\"migrate\",\"attempted\":1,\"migrated\":0,"
                    + "\"skippedProcessing\":0,\"failed\":1,\"orphanMigrated\":0,\"legacyRows\":1,\"encryptedRows\":0,"
                    + "\"unsupportedRows\":0,\"processingLegacyRows\":0,\"orphanRows\":0,\"finalized\":false}"
                    + System.lineSeparator());
            assertThat(output.getErr()).isEqualTo(errorsBefore);
            assertThat(migrations.constructed()).hasSize(1);
        }
    }

    @Test
    @DisplayName("전환은 기본 100개 행과 한 번의 후보 조회로 실행 범위를 제한한다")
    void defaultToBoundedMigration() {
        assertThat(DiarySourceTextBackfillMain.parse(new String[]{"migrate"}))
                .isEqualTo(new DiarySourceTextBackfillMain.Options("migrate", 100, 1));
        assertThat(DiarySourceTextBackfillMain.parse(new String[]{
                "migrate", "--batch-size", "1000", "--max-batches", "10000"
        })).isEqualTo(new DiarySourceTextBackfillMain.Options("migrate", 1000, 10000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1001", "2147483648", "1.5", "01", "secret-value"})
    @DisplayName("배치 크기의 잘못된 값과 상한 초과를 입력값 노출 없이 거절한다")
    void rejectInvalidLimits(String limit) {
        assertThatThrownBy(() -> DiarySourceTextBackfillMain.parse(new String[]{"migrate", "--batch-size", limit}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid maintenance limit.");
    }

    @Test
    @DisplayName("조회 명령의 쓰기 옵션과 중복 또는 알 수 없는 옵션을 거절한다")
    void rejectAmbiguousOrUnsupportedOperations() {
        for (String[] arguments : new String[][]{
                {}, {"unsupported"}, {"status", "--batch-size", "1"},
                {"ready", "--max-batches", "1"}, {"verify", "--max-batches", "1"},
                {"finalize", "--max-batches", "1"}, {"migrate", "--batch-size"},
                {"migrate", "--batch-size", "1", "--batch-size", "2"},
                {"migrate", "--unknown", "1"}, {"migrate", "--max-batches", "10001"}
        }) {
            assertThatThrownBy(() -> DiarySourceTextBackfillMain.parse(arguments))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private void assertFailure(CapturedOutput output, String[] args, MockEnvironment environment,
                               String command, String code) {
        String before = output.getOut();
        String errorsBefore = output.getErr();

        assertThat(DiarySourceTextBackfillMain.execute(args, environment)).isEqualTo(1);

        assertThat(output.getOut()).isEqualTo(before + "{\"command\":\"" + command + "\",\"errorCode\":\"" + code
                + "\"}" + System.lineSeparator());
        assertThat(output.getErr()).isEqualTo(errorsBefore);
    }

    private MockEnvironment verifiedEnvironment() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        Path verification = directory.resolve("checks.json");
        new DiarySourceTextKeyVerification(new DiarySourceTextEncryptionProperties("primary", Map.of("primary", key)))
                .createInitialEvidence(verification, "dev");
        return new TestEnvironment()
                .withProperty("spring.config.location", "optional:classpath:/missing-backfill-cli-test.yml")
                .withProperty("DEPLOY_ENV", "dev")
                .withProperty(PREFIX + "active-key-id", "primary")
                .withProperty(PREFIX + "keys.primary", key)
                .withProperty(PREFIX + "verification-file", verification.toString())
                .withProperty(PREFIX + "new-writes-enabled", "false")
                .withProperty("spring.datasource.url", DATABASE_URL)
                .withProperty("spring.datasource.username", "test")
                .withProperty("spring.datasource.password", "test");
    }

    private static final class TestEnvironment extends MockEnvironment {

        @Override
        public Map<String, Object> getSystemEnvironment() {
            return Map.of();
        }
    }
}
