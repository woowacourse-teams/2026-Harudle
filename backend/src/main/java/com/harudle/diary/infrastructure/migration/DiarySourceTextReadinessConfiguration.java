package com.harudle.diary.infrastructure.migration;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.ConfigurableEnvironment;

@Configuration(proxyBeanMethods = false)
public class DiarySourceTextReadinessConfiguration {

    /** Refuses deployed v1-only startup until the database cutover has been explicitly finalized. */
    @Bean
    @Lazy(false)
    @DependsOnDatabaseInitialization
    InitializingBean diarySourceTextMigrationReadiness(
            DataSource dataSource,
            ConfigurableEnvironment environment
    ) {
        return () -> {
            if (environment.matchesProfiles("prod")
                    || environment.getSystemEnvironment().containsKey("DIARY_SOURCE_TEXT_RELEASE_MODE")) {
                verifyDatabase(dataSource);
            }
        };
    }

    private void verifyDatabase(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            new DiarySourceTextReadiness(connection).requireReady();
        } catch (RuntimeException | SQLException exception) {
            throw new IllegalStateException("Diary source text migration is not ready.");
        }
    }
}
