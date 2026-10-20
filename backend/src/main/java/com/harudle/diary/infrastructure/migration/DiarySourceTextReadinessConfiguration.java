package com.harudle.diary.infrastructure.migration;

import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

@Configuration(proxyBeanMethods = false)
public class DiarySourceTextReadinessConfiguration {

    /** Refuses deployed v1-only startup until the database cutover has been explicitly finalized. */
    @Bean
    @DependsOnDatabaseInitialization
    InitializingBean diarySourceTextMigrationReadiness(
            DataSource dataSource,
            DiarySourceTextCipher cipher,
            RequestFingerprintGenerator fingerprints,
            ConfigurableEnvironment environment
    ) {
        return () -> {
            if (environment.matchesProfiles("prod")
                    || environment.getSystemEnvironment().containsKey("DIARY_SOURCE_TEXT_RELEASE_MODE")) {
                verifyDatabase(dataSource, cipher, fingerprints);
            }
        };
    }

    private void verifyDatabase(DataSource dataSource, DiarySourceTextCipher cipher,
            RequestFingerprintGenerator fingerprints) {
        try (Connection connection = dataSource.getConnection()) {
            new DiarySourceTextMigration(connection, cipher, fingerprints).requireReady();
        } catch (RuntimeException | SQLException exception) {
            throw new IllegalStateException("Diary source text migration is not ready.");
        }
    }
}
