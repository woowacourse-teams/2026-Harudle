package com.harudle.diary.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DiarySourceTextReadinessTest {

    private static final String FAILURE_MESSAGE = "일기 원문 전환 작업에 실패했습니다.";
    private static final Pattern USER_DATA_QUERY = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+(?:public\\.)?(?:diaries|diary_generations)\\b", Pattern.CASE_INSENSITIVE
    );

    // These tests replace tables and constraints, so they must not use the shared service-test database.
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine")
    );

    private Connection connection;

    @BeforeEach
    void createSupportedSchema() throws SQLException {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        execute("DROP TABLE IF EXISTS public.diary_generations, public.diaries CASCADE");
        execute("""
                CREATE TABLE public.diaries (
                    id uuid PRIMARY KEY, user_id uuid NOT NULL, diary_date date NOT NULL,
                    source_text text NOT NULL, source_text_version integer NOT NULL DEFAULT 0
                )
                """);
        execute("""
                CREATE TABLE public.diary_generations (
                    id uuid PRIMARY KEY, diary_id uuid NOT NULL,
                    CONSTRAINT uq_readiness_diary UNIQUE (diary_id)
                )
                """);
    }

    @AfterEach
    void closeConnection() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    @DisplayName("완료 확인은 사용자 테이블 SELECT 없이 검증된 제약을 확인하며 어떤 저장값도 읽거나 바꾸지 않는다")
    void provesReadinessWithoutReadingUserRows() throws SQLException {
        finalizeSchema();
        execute("""
                INSERT INTO public.diaries (id, user_id, diary_date, source_text, source_text_version)
                VALUES ('f7252889-084e-4d78-b6b0-721dba92c420', '81835ab1-cdec-47e8-9d26-74663d6ab2dc',
                        DATE '2026-11-07', '원문이나 암호문의 유효성은 verify에서 검사한다', 1)
                """);
        List<String> statements = new ArrayList<>();
        Connection metadataOnly = observe(connection, (method, args) -> {
            if ("prepareStatement".equals(method)) {
                String sql = (String) args[0];
                assertThat(USER_DATA_QUERY.matcher(sql).find()).as("readiness must not select application rows").isFalse();
                statements.add(sql);
            }
        });

        new DiarySourceTextReadiness(metadataOnly).requireReady();

        assertThat(statements).anyMatch(sql -> sql.contains("pg_constraint"));
        assertThat(connection.getAutoCommit()).isTrue();
        assertThat(connection.isClosed()).isFalse();
        try (var statement = connection.prepareStatement("SELECT source_text FROM public.diaries");
                var row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo("원문이나 암호문의 유효성은 verify에서 검사한다");
        }
        assertThatThrownBy(() -> execute("""
                INSERT INTO public.diaries (id, user_id, diary_date, source_text, source_text_version)
                VALUES ('ac0cafbb-858c-4383-a479-b07f2f5d6452', '81835ab1-cdec-47e8-9d26-74663d6ab2dc',
                        DATE '2026-11-07', '허용할 수 없는 평문 버전', 0)
                """)).isInstanceOfSatisfying(SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
    }

    @Test
    @DisplayName("평문 백업이나 최종화 전 DB는 빈 테이블이어도 완료로 인정하지 않는다")
    void rejectsUnfinalizedSchemaEvenWhenNoRowsExist() {
        assertRejected();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteOrUnsupportedSchemas")
    @DisplayName("완료 제약의 이름뿐 아니라 검증 여부와 내용, 기본값, 필수값과 지원 스키마를 함께 검사한다")
    void rejectsIncompleteOrAlteredSchema(String name, List<String> mutations) throws SQLException {
        finalizeSchema();
        for (String mutation : mutations) {
            execute(mutation);
        }
        assertRejected();
        assertThat(connection.isClosed()).isFalse();
        assertThat(connection.getAutoCommit()).isTrue();
    }

    static Stream<Arguments> incompleteOrUnsupportedSchemas() {
        String dropCheck = "ALTER TABLE public.diaries DROP CONSTRAINT ck_diaries_source_text_encrypted_only";
        String addCheck = "ALTER TABLE public.diaries ADD CONSTRAINT ck_diaries_source_text_encrypted_only ";
        return Stream.of(
                Arguments.of("제약 누락", List.of(dropCheck)),
                Arguments.of("아직 기존 행을 검증하지 않은 규칙", List.of(dropCheck,
                        addCheck + "CHECK (source_text_version = 1) NOT VALID")),
                Arguments.of("평문을 허용하는 느슨한 규칙", List.of(dropCheck,
                        addCheck + "CHECK (source_text_version IN (0, 1))")),
                Arguments.of("NOT ENFORCED 규칙", List.of(dropCheck,
                        addCheck + "CHECK (source_text_version = 1) NOT ENFORCED")),
                Arguments.of("다른 열이 섞인 규칙", List.of(dropCheck,
                        addCheck + "CHECK (source_text_version = 1 AND user_id IS NOT NULL)")),
                Arguments.of("NO INHERIT 규칙", List.of(dropCheck,
                        addCheck + "CHECK (source_text_version = 1) NO INHERIT")),
                Arguments.of("버전 기본값 잔존", List.of(
                        "ALTER TABLE public.diaries ALTER COLUMN source_text_version SET DEFAULT 0")),
                Arguments.of("nullable 버전", List.of(
                        "ALTER TABLE public.diaries ALTER COLUMN source_text_version DROP NOT NULL")),
                Arguments.of("일기 RLS", List.of("ALTER TABLE public.diaries ENABLE ROW LEVEL SECURITY")),
                Arguments.of("생성 기록 RLS", List.of("ALTER TABLE public.diary_generations ENABLE ROW LEVEL SECURITY")),
                Arguments.of("필수 원문 열 형식 변경", List.of(
                        "ALTER TABLE public.diaries ALTER COLUMN source_text TYPE varchar(100)")),
                Arguments.of("생성 기록의 일기 UNIQUE 누락", List.of(
                        "ALTER TABLE public.diary_generations DROP CONSTRAINT uq_readiness_diary"))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"commit", "rollback"})
    @DisplayName("저장 또는 취소 응답이 불명확하면 연결을 폐기하고 자동 커밋을 복원하지 않는다")
    void discardsConnectionAfterUncertainTransactionEnd(String failedMethod) throws SQLException {
        if ("commit".equals(failedMethod)) {
            finalizeSchema();
        }
        List<String> calls = new ArrayList<>();
        AtomicBoolean faultInjected = new AtomicBoolean();
        Connection uncertain = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    calls.add(method.getName() + ("setAutoCommit".equals(method.getName()) ? ":" + args[0] : ""));
                    try {
                        Object result = method.invoke(connection, args);
                        if (failedMethod.equals(method.getName()) && faultInjected.compareAndSet(false, true)) {
                            throw new SQLException("private-transaction-diagnostic");
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });

        assertThatThrownBy(() -> new DiarySourceTextReadiness(uncertain).requireReady())
                .isExactlyInstanceOf(DiarySourceTextMigrationException.class)
                .hasMessage(FAILURE_MESSAGE).hasNoCause();

        assertThat(faultInjected).isTrue();
        assertThat(calls).contains("abort").doesNotContain("setAutoCommit:true");
    }

    private void finalizeSchema() throws SQLException {
        execute("""
                ALTER TABLE public.diaries ADD CONSTRAINT ck_diaries_source_text_encrypted_only
                CHECK (source_text_version = 1)
                """);
        execute("ALTER TABLE public.diaries ALTER COLUMN source_text_version DROP DEFAULT");
    }

    private void assertRejected() {
        assertThatThrownBy(() -> new DiarySourceTextReadiness(connection).requireReady())
                .isExactlyInstanceOf(DiarySourceTextMigrationException.class)
                .hasMessage(FAILURE_MESSAGE).hasNoCause();
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection observe(Connection delegate, Observation observation) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    observation.accept(method.getName(), args);
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    @FunctionalInterface
    private interface Observation {
        void accept(String method, Object[] args);
    }
}
