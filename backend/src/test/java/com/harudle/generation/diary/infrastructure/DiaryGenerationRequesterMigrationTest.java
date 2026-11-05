package com.harudle.generation.diary.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DiaryGenerationRequesterMigrationTest {

    private static final MigrationVersion LAST_VERSION_WITHOUT_REQUESTER = MigrationVersion.fromVersion("43");
    private static final String NOT_NULL_VIOLATION_SQL_STATE = "23502";

    @Container
    private static final PostgreSQLContainer POSTGRESQL_CONTAINER =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @BeforeEach
    void migratePreviousSchema() {
        flyway().load().clean();
        flyway().target(LAST_VERSION_WITHOUT_REQUESTER).load().migrate();
    }

    @Test
    @DisplayName("기존 생성 기록의 요청자를 해당 일기 작성자로 채운다")
    void backfillRequesterFromDiaryOwner() throws SQLException {
        UUID firstOwner = insertUser();
        UUID secondOwner = insertUser();
        long promptId = insertPrompt();
        UUID firstGeneration = insertGenerationWithoutRequester(insertDiary(firstOwner), promptId);
        UUID secondGeneration = insertGenerationWithoutRequester(insertDiary(secondOwner), promptId);

        flyway().load().migrate();

        assertThat(readRequester(firstGeneration)).isEqualTo(firstOwner);
        assertThat(readRequester(secondGeneration)).isEqualTo(secondOwner);
    }

    @Test
    @DisplayName("DB를 변경한 뒤에는 요청자 없이 생성 기록을 저장할 수 없다")
    void rejectGenerationWithoutRequesterAfterMigration() throws SQLException {
        flyway().load().migrate();
        UUID diaryId = insertDiary(insertUser());
        long promptId = insertPrompt();

        SQLException exception = catchThrowableOfType(
                SQLException.class,
                () -> insertGenerationWithoutRequester(diaryId, promptId)
        );

        assertThat(exception.getSQLState()).isEqualTo(NOT_NULL_VIOLATION_SQL_STATE);
        assertThat(exception.getMessage()).contains("requested_by_user_id");
    }

    private UUID insertUser() throws SQLException {
        UUID userId = UUID.randomUUID();
        execute("INSERT INTO users (id, name) VALUES (?, '요청자 테스트')", userId);
        return userId;
    }

    private UUID insertDiary(UUID userId) throws SQLException {
        UUID diaryId = UUID.randomUUID();
        execute("""
                INSERT INTO diaries (id, user_id, diary_date, source_text)
                VALUES (?, ?, DATE '2026-11-05', '요청자 컬럼을 확인하는 일기')
                """, diaryId, userId);
        return diaryId;
    }

    private long insertPrompt() throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO generation_prompts (
                            storyboard_prompt_text, image_style_prompt_text, image_asset_object_key
                        )
                        VALUES ('스토리보드 프롬프트', '이미지 스타일 프롬프트', 'references/style.png')
                        RETURNING id
                        """);
                ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private UUID insertGenerationWithoutRequester(UUID diaryId, long promptId) throws SQLException {
        UUID generationId = UUID.randomUUID();
        execute("""
                INSERT INTO diary_generations (
                    id, diary_id, prompt_id, idempotency_key, request_fingerprint, status
                )
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                """, generationId, diaryId, promptId, UUID.randomUUID(), "a".repeat(64));
        return generationId;
    }

    private UUID readRequester(UUID generationId) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT requested_by_user_id
                        FROM diary_generations
                        WHERE id = ?
                        """)) {
            statement.setObject(1, generationId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            statement.executeUpdate();
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRESQL_CONTAINER.getJdbcUrl(),
                POSTGRESQL_CONTAINER.getUsername(),
                POSTGRESQL_CONTAINER.getPassword()
        );
    }

    private static FluentConfiguration flyway() {
        return Flyway.configure()
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .dataSource(
                        POSTGRESQL_CONTAINER.getJdbcUrl(),
                        POSTGRESQL_CONTAINER.getUsername(),
                        POSTGRESQL_CONTAINER.getPassword()
                )
                .cleanDisabled(false);
    }
}
