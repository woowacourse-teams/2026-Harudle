package com.harudle;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class HarudleApplicationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private Flyway flyway;

    @Autowired
    private FlywayProperties flywayProperties;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("Spring 애플리케이션 컨텍스트를 로드한다")
    void contextLoads() {
    }

    @Test
    @DisplayName("Flyway는 마이그레이션별 트랜잭션과 PostgreSQL 세션 단위 잠금을 사용한다")
    void configureFlywayForConcurrentIndexes() {
        assertThat(flyway.getConfiguration().isGroup()).isFalse();
        assertThat(flywayProperties.getPostgresql().getTransactionalLock()).isFalse();
    }

    @Test
    @DisplayName("Flyway가 초기 스키마를 생성한다")
    void schemaIsMigrated() {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """).getResultList();

        List<String> tableNames = rows.stream()
                .map(String.class::cast)
                .toList();

        assertThat(tableNames).containsExactly(
                "admin_generation_usage_restores",
                "categories",
                "comments",
                "daily_generation_usage",
                "diaries",
                "diary_generations",
                "feed_likes",
                "feeds",
                "generation_prompts",
                "guest_sessions",
                "notification_outbox",
                "notifications",
                "oauth_accounts",
                "push_registrations",
                "refresh_tokens",
                "share_links",
                "users"
        );
    }
}
