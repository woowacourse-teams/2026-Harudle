package com.harudle.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ProfileImageCleanupMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Test
    @DisplayName("V34는 기존 삭제 작업과 유예 시각을 유지하면서 회원별 조회 구조로 변경한다")
    void preservesPendingTasksWhenRenamingDeletionTime() {
        var dataSource = new DriverManagerDataSource(
                POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword()
        );
        var jdbc = new JdbcTemplate(dataSource);
        migrate(dataSource, "33");
        UUID userId = UUID.randomUUID();
        String key = "harudle/profile-images/dev/" + userId + "/" + UUID.randomUUID() + ".webp";
        Instant createdAt = Instant.parse("2026-10-20T00:00:00Z");
        Instant deleteAfter = createdAt.plusSeconds(60);
        jdbc.update("""
                INSERT INTO profile_image_cleanup_tasks (object_key, user_id, created_at, next_attempt_at)
                VALUES (?, ?, ?, ?)
                """, key, userId, java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(deleteAfter));

        assertThat(migrate(dataSource, "34")).isEqualTo(1);

        var repository = new ProfileImageCleanupRepository(jdbc);
        assertThat(repository.findPending(userId, createdAt, 50)).isEmpty();
        assertThat(repository.findPending(userId, deleteAfter, 50))
                .containsExactly(new ProfileImageCleanupRepository.PendingCleanup(userId, key));
        assertThat(repository.findPending(UUID.randomUUID(), deleteAfter, 50)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT created_at FROM profile_image_cleanup_tasks",
                java.sql.Timestamp.class).toInstant()).isEqualTo(createdAt);
    }

    private int migrate(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                .target(target)
                .load()
                .migrate().migrationsExecuted;
    }
}
