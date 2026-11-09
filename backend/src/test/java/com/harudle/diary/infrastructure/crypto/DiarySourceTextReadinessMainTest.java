package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import com.harudle.diary.infrastructure.migration.DiarySourceTextReadiness;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class DiarySourceTextReadinessMainTest {

    private static final String PREFIX = "harudle.diary.source-text-encryption.";
    private static final String DATABASE_URL = "jdbc:harudle-readiness-test:no-network";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @TempDir
    private Path directory;

    @Test
    @DisplayName("완료 전용 CLI는 키를 검증하고 메타데이터 확인 뒤 정확한 성공 JSON 하나만 출력한다")
    void reportsOneExactReadinessResultWithoutDataCounts(CapturedOutput output) throws SQLException {
        MockEnvironment environment = verifiedEnvironment();
        Connection connection = mock(Connection.class);
        try (var checks = mockConstruction(DiarySourceTextReadiness.class);
                var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(any(String.class), any(Properties.class)))
                    .thenReturn(connection);
            String before = output.getOut();
            String errorsBefore = output.getErr();

            assertThat(DiarySourceTextReadinessMain.execute(new String[]{"ready"}, environment)).isZero();

            assertThat(output.getOut()).isEqualTo(before + "{\"command\":\"ready\",\"ready\":true}"
                    + System.lineSeparator());
            assertThat(output.getErr()).isEqualTo(errorsBefore);
            assertThat(checks.constructed()).hasSize(1);
            verify(checks.constructed().getFirst()).requireReady();
            verify(connection).close();
        }
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    @DisplayName("ready 하나 외의 명령과 옵션은 입력을 출력하거나 DB에 접속하지 않고 거절한다")
    void refusesUnknownArgumentsWithoutEchoOrConnection(String[] arguments, CapturedOutput output) {
        try (var driver = mockStatic(DriverManager.class)) {
            assertFailure(output, arguments, new TestEnvironment(), "CONFIGURATION_INVALID");
            driver.verifyNoInteractions();
        }
    }

    static Stream<Arguments> invalidArguments() {
        return Stream.of(
                Arguments.of((Object) null), Arguments.of((Object) new String[]{}),
                Arguments.of((Object) new String[]{null}), Arguments.of((Object) new String[]{"status"}),
                Arguments.of((Object) new String[]{"migrate"}),
                Arguments.of((Object) new String[]{"ready", "--batch-size", "private-value"}),
                Arguments.of((Object) new String[]{"ready", "ready"})
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("검증 자료 누락과 동일 ID의 다른 키는 DB 연결 전에 고정된 키 오류로 거절한다")
    void verifiesCanaryBeforeConnecting(boolean missingFile, CapturedOutput output) {
        MockEnvironment environment = verifiedEnvironment();
        if (missingFile) {
            environment.setProperty(PREFIX + "verification-file", directory.resolve("missing-private.json").toString());
        } else {
            byte[] wrongKey = new byte[32];
            wrongKey[0] = 1;
            environment.setProperty(PREFIX + "keys.primary", Base64.getEncoder().encodeToString(wrongKey));
        }
        try (var driver = mockStatic(DriverManager.class)) {
            assertFailure(output, new String[]{"ready"}, environment, "KEY_VERIFICATION_FAILED");
            driver.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("배포 작성 정책이 다르면 키 확인이나 DB 연결 전에 설정 오류로 중단한다")
    void requiresArtifactWritePolicy(CapturedOutput output) {
        MockEnvironment environment = verifiedEnvironment();
        environment.setActiveProfiles("prod");
        try (var driver = mockStatic(DriverManager.class)) {
            assertFailure(output, new String[]{"ready"}, environment, "CONFIGURATION_INVALID");
            driver.verifyNoInteractions();
        }
    }

    @Test
    @DisplayName("DB 접속 실패는 접속 정보와 원인을 출력하지 않고 실패 단계만 알린다")
    void hidesDatabaseConnectionDiagnostics(CapturedOutput output) {
        MockEnvironment environment = verifiedEnvironment();
        SQLException failure = new SQLException("private-password-and-query");
        try (var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(any(String.class), any(Properties.class)))
                    .thenThrow(failure);
            assertFailure(output, new String[]{"ready"}, environment, "DATABASE_CONNECTION_FAILED");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("완료 검사나 연결 종료 실패에는 성공 JSON을 출력하지 않는다")
    void reportsNoSuccessWhenCheckOrCloseFails(boolean closeFails, CapturedOutput output) throws SQLException {
        MockEnvironment environment = verifiedEnvironment();
        Connection connection = mock(Connection.class);
        if (closeFails) {
            doThrow(new SQLException("private-close-diagnostic")).when(connection).close();
        }
        try (var checks = mockConstruction(DiarySourceTextReadiness.class, (check, context) -> {
                    if (!closeFails) {
                        doThrow(new IllegalStateException("private-database-diagnostic")).when(check).requireReady();
                    }
                });
                var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(any(String.class), any(Properties.class)))
                    .thenReturn(connection);
            assertFailure(output, new String[]{"ready"}, environment, "DATABASE_OPERATION_FAILED");
            assertThat(checks.constructed()).hasSize(1);
        }
    }

    @Test
    @DisplayName("예상하지 못한 Error와 suppressed 원인은 고정된 오류 코드 밖으로 노출하지 않는다")
    void hidesFatalErrors(CapturedOutput output) {
        MockEnvironment environment = verifiedEnvironment();
        Error fatal = new AssertionError("private-key-and-plaintext");
        fatal.addSuppressed(new SQLException("private-suppressed-query"));
        try (var checks = mockConstruction(DiarySourceTextReadiness.class, (check, context) ->
                    doThrow(fatal).when(check).requireReady());
                var driver = mockStatic(DriverManager.class)) {
            driver.when(() -> DriverManager.getConnection(any(String.class), any(Properties.class)))
                    .thenReturn(mock(Connection.class));
            assertFailure(output, new String[]{"ready"}, environment, "UNEXPECTED_FAILURE");
            assertThat(checks.constructed()).hasSize(1);
        }
    }

    private void assertFailure(CapturedOutput output, String[] args, MockEnvironment environment, String code) {
        String before = output.getOut();
        String errorsBefore = output.getErr();
        assertThat(DiarySourceTextReadinessMain.execute(args, environment)).isEqualTo(1);
        assertThat(output.getOut()).isEqualTo(before + "{\"command\":\"ready\",\"errorCode\":\"" + code + "\"}"
                + System.lineSeparator());
        assertThat(output.getErr()).isEqualTo(errorsBefore);
    }

    private MockEnvironment verifiedEnvironment() {
        Path verification = directory.resolve("checks.json");
        new DiarySourceTextKeyVerification(new DiarySourceTextEncryptionProperties("primary", Map.of("primary", KEY)))
                .createInitialEvidence(verification, "dev");
        return new TestEnvironment()
                .withProperty("spring.config.location", "optional:classpath:/missing-readiness-cli-test.yml")
                .withProperty("DEPLOY_ENV", "dev")
                .withProperty(PREFIX + "active-key-id", "primary")
                .withProperty(PREFIX + "keys.primary", KEY)
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
