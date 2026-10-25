package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DiarySourceTextSchemaMigrationTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID DIARY_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final String LEGACY_TEXT = "마이그레이션 전 보존할 일기 🌿";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @BeforeEach
    void prepareActualPreEncryptionSchema() throws SQLException, IOException {
        Flyway previous = flyway("35");
        previous.clean();
        previous.migrate();
        execute("INSERT INTO public.users (id, name) VALUES (?, ?)", USER_ID, "스키마 검증 사용자");
        execute("INSERT INTO public.diaries (id, user_id, diary_date, source_text) VALUES (?, ?, ?, ?)",
                DIARY_ID, USER_ID, LocalDate.of(2026, 10, 21), LEGACY_TEXT);
    }

    @Test
    @DisplayName("V36의 잠금 대기는 정책 제한으로 실패하고 스키마와 평문을 보존한 뒤 재실행할 수 있다")
    void lockTimeoutRollsBackSchemaAndAllowsRetry() throws SQLException, IOException {
        Flyway next = flyway("36");
        try (Connection blocker = openConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "LOCK TABLE public.diaries IN ACCESS SHARE MODE")) {
                lock.execute();
            }

            FlywayException failure = catchThrowableOfType(FlywayException.class, next::migrate);

            assertThat((Throwable) failure).isNotNull();
            assertThat(sqlCause(failure).getSQLState()).isEqualTo("55P03");
            assertLegacySchemaAndDataUnchanged();
            blocker.rollback();
        }

        next.migrate();

        assertThat(scalar("SELECT source_text_version FROM public.diaries WHERE id = ?", DIARY_ID)).isEqualTo(0);
        assertThat(scalar("SELECT source_text FROM public.diaries WHERE id = ?", DIARY_ID)).isEqualTo(LEGACY_TEXT);
        assertThat(scalar("SELECT success FROM public.flyway_schema_history WHERE version = '36'")).isEqualTo(true);
        next.validate();
    }

    @Test
    @DisplayName("V36은 기존 평문을 유지하며 새 저장 버전의 범위를 데이터베이스 제약으로 제한한다")
    void upgradePreservesLegacyTextAndRejectsUnsupportedVersions() throws SQLException, IOException {
        flyway("36").migrate();

        assertThat(scalar("SELECT source_text FROM public.diaries WHERE id = ?", DIARY_ID)).isEqualTo(LEGACY_TEXT);
        assertThat(scalar("SELECT source_text_version FROM public.diaries WHERE id = ?", DIARY_ID)).isEqualTo(0);
        SQLException failure = catchThrowableOfType(SQLException.class, () -> execute("""
                INSERT INTO public.diaries (id, user_id, diary_date, source_text, source_text_version)
                VALUES (?, ?, ?, ?, 2)
                """, UUID.randomUUID(), USER_ID, LocalDate.of(2026, 10, 22), "지원하지 않는 버전"));

        assertThat((Throwable) failure).isNotNull();
        assertThat(failure.getSQLState()).isEqualTo("23514");
        assertThat(scalar("SELECT COUNT(*) FROM public.diaries")).isEqualTo(1L);
    }

    private void assertLegacySchemaAndDataUnchanged() throws SQLException {
        assertThat(scalar("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'diaries' AND column_name = 'source_text'
                """)).isEqualTo("character varying");
        assertThat(scalar("""
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'diaries' AND column_name = 'source_text'
                """)).isEqualTo(300);
        assertThat(scalar("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'diaries' AND column_name = 'source_text_version'
                """)).isEqualTo(0L);
        assertThat(scalar("""
                SELECT COUNT(*) FROM pg_catalog.pg_constraint
                WHERE conrelid = 'public.diaries'::regclass AND conname = 'ck_diaries_source_text_version'
                """)).isEqualTo(0L);
        assertThat(scalar("SELECT COUNT(*) FROM public.flyway_schema_history WHERE version = '36'")).isEqualTo(0L);
        assertThat(scalar("SELECT source_text FROM public.diaries WHERE id = ?", DIARY_ID)).isEqualTo(LEGACY_TEXT);
        assertThat(scalar("SELECT COUNT(*) FROM public.diaries")).isEqualTo(1L);
    }

    private Flyway flyway(String target) throws IOException {
        List<String> initSqls = migrationProperties().getInitSqls();
        assertThat(initSqls).containsExactly("SET lock_timeout = '5s';", "SET statement_timeout = '60s';");
        return Flyway.configure()
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .initSql(String.join("\n", initSqls))
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    private FlywayProperties migrationProperties() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("migration-test", Map.of(
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword()
        )));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return Binder.get(environment).bind("spring.flyway", Bindable.of(FlywayProperties.class))
                .orElseThrow(() -> new IllegalStateException("Flyway 설정이 필요합니다."));
    }

    private SQLException sqlCause(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
                .filter(SQLException.class::isInstance)
                .map(SQLException.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("SQL 예외가 필요합니다."));
    }

    private void execute(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.execute();
        }
    }

    private Object scalar(String sql, Object... values) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1);
            }
        }
    }

    private void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
