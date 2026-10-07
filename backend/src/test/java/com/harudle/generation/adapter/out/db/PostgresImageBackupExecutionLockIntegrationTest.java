package com.harudle.generation.adapter.out.db;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class PostgresImageBackupExecutionLockIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Test
    void anotherSessionIsExcludedAndPoolDoesNotRetainSessionLockAfterCompletion() {
        try (var pool = pool()) {
            var first = new PostgresImageBackupExecutionLock(pool);
            var other = new PostgresImageBackupExecutionLock(
                    new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
            assertThat(first.executeIfAvailable(() -> assertThat(other.executeIfAvailable(
                    () -> { throw new AssertionError("another session entered"); })).isFalse())).isTrue();
            assertThat(other.executeIfAvailable(() -> {})).isTrue();
        }
    }

    @Test
    void failedOperationReleasesLockBeforeConnectionReturnsToPool() {
        try (var pool = pool()) {
            var first = new PostgresImageBackupExecutionLock(pool);
            assertThatThrownBy(() -> first.executeIfAvailable(() -> { throw new IllegalStateException("failed"); }))
                    .isInstanceOf(IllegalStateException.class);
            var other = new PostgresImageBackupExecutionLock(
                    new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
            assertThat(other.executeIfAvailable(() -> {})).isTrue();
        }
    }

    private HikariDataSource pool() {
        var config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }
}
