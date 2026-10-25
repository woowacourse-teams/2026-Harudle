package com.harudle;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.support.ServiceIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class HarudleApplicationTests extends ServiceIntegrationTestSupport {

    @Autowired
    private Flyway flyway;

    @Autowired
    private FlywayProperties flywayProperties;

    @Autowired
    private DataSource dataSource;

    @PersistenceContext
    private EntityManager entityManager;

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
    @DisplayName("Flyway 잠금 대기와 문장 실행의 정책 제한을 바인딩하고 실제 설정에 적용한다")
    void applyBoundFlywaySessionLimitsToEffectiveConfiguration() {
        assertThat(flywayProperties.getInitSqls())
                .containsExactly("SET lock_timeout = '5s';", "SET statement_timeout = '60s';");
        assertThat(flyway.getConfiguration().getInitSql()).isEqualTo(String.join("\n", flywayProperties.getInitSqls()));
    }

    @Test
    @DisplayName("Flyway 전용 연결의 세션 제한은 일반 애플리케이션 풀에 전파하지 않는다")
    void keepMigrationSessionLimitsOutOfApplicationPool() throws SQLException {
        assertThat(flyway.getConfiguration().getDataSource()).isNotSameAs(dataSource)
                .isInstanceOf(SimpleDriverDataSource.class);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT current_setting('lock_timeout'), current_setting('statement_timeout')")) {
            try (ResultSet settings = statement.executeQuery()) {
                assertThat(settings.next()).isTrue();
                assertThat(settings.getString(1)).isEqualTo("0");
                assertThat(settings.getString(2)).isEqualTo("0");
            }
        }
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
                "auth_sessions",
                "categories",
                "comments",
                "daily_generation_usage",
                "diaries",
                "diary_generations",
                "feed_likes",
                "feeds",
                "generation_prompts",
                "guest_sessions",
                "moderation_events",
                "moderation_states",
                "nickname_candidates",
                "notification_outbox",
                "notifications",
                "oauth_accounts",
                "profile_image_cleanup_tasks",
                "push_registration_states",
                "push_registrations",
                "refresh_tokens",
                "reports",
                "share_links",
                "users"
        );
    }
}
