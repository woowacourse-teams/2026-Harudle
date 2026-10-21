package com.harudle.diary.infrastructure.crypto;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * Keeps Flyway session limits on physical migration connections instead of returning them to the application pool.
 * Connection details support Flyway properties and service connections; separate {@code @FlywayDataSource} beans
 * are outside this application's connection configuration contract.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DiarySourceTextFlywayConfiguration {

    @Bean
    public FlywayConfigurationCustomizer diarySourceTextFlywayConnectionIsolation(
            JdbcConnectionDetails application,
            FlywayConnectionDetails migration
    ) {
        return configuration -> {
            var source = DataSourceBuilder.create().type(SimpleDriverDataSource.class)
                    .url(configuredOrDefault(migration.getJdbcUrl(), application.getJdbcUrl()))
                    .username(configuredOrDefault(migration.getUsername(), application.getUsername()))
                    .password(configuredOrDefault(migration.getPassword(), application.getPassword()));
            String driver = configuredOrDefault(migration.getDriverClassName(), application.getDriverClassName());
            if (driver != null) {
                source.driverClassName(driver);
            }
            configuration.dataSource(source.build());
        };
    }

    private @Nullable String configuredOrDefault(@Nullable String configured, @Nullable String fallback) {
        return configured != null ? configured : fallback;
    }
}
