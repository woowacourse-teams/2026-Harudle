package com.harudle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class FlywayMigrationVersionTest {

    @Test
    @DisplayName("DB 연결 없이도 Flyway SQL 마이그레이션의 버전 중복을 검사한다")
    void versionedSqlMigrationsHaveUniqueVersions() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");
        assertThat(scripts).isNotEmpty();
        Map<MigrationVersion, List<String>> scriptsByVersion = new TreeMap<>();
        for (Resource script : scripts) {
            String filename = script.getFilename();
            assertThat(filename).isNotNull();
            MigrationVersion version = MigrationVersion.fromVersion(filename.substring(1, filename.indexOf("__")));
            scriptsByVersion.computeIfAbsent(version, ignored -> new ArrayList<>()).add(filename);
        }

        assertThat(scriptsByVersion.entrySet().stream().filter(entry -> entry.getValue().size() > 1).toList())
                .as("같은 Flyway 버전을 사용하는 SQL 파일")
                .isEmpty();
    }
}
