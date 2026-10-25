package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
            String outputBefore = output.getOut();
            String errorBefore = output.getErr();

            assertThat(DiarySourceTextBackfillMain.execute(new String[]{"status"}, environment)).isEqualTo(1);
            assertThat(output.getOut()).isEqualTo(outputBefore);
            assertThat(output.getErr()).isEqualTo(errorBefore);

            driver.verify(() -> DriverManager.getConnection(any(String.class), any(Properties.class)));
        }

        verify(connection).getAutoCommit();
        verify(connection).close();
        assertThat(failure.getSuppressed()).contains(closeFailure);
        assertThat(output.getOut() + output.getErr()).doesNotContain("private-error-sentinel",
                "private-cause-sentinel", "private-suppressed-sentinel", "private-close-sentinel");
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
