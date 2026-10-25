package com.harudle.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 실제 Flyway 스키마를 쓰는 차단 회귀의 전용 로컬 DB 또는 JVM 공유 테스트 컨테이너 연결만 제공한다. */
@ExtendWith(BlockDatabaseTestSupport.DatabaseAvailable.class)
public abstract class BlockDatabaseTestSupport {
    private static PostgreSQLContainer postgres;

    @DynamicPropertySource
    static synchronized void databaseProperties(DynamicPropertyRegistry properties) {
        String url = System.getenv("HARUDLE_BLOCK_TEST_DATABASE_URL");
        if (url != null) {
            requireDedicatedDatabase(url);
            configure(properties, url, environmentOrDefault("HARUDLE_BLOCK_TEST_DATABASE_USERNAME",
                    "harudle_block_test"), environmentOrDefault("HARUDLE_BLOCK_TEST_DATABASE_PASSWORD", ""));
            return;
        }
        if (postgres == null) {
            postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
            postgres.start();
        }
        configure(properties, postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void configure(DynamicPropertyRegistry properties, String url, String user, String password) {
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.flyway.url", () -> url);
        properties.add("spring.flyway.user", () -> user);
        properties.add("spring.flyway.password", () -> password);
    }

    private static void requireDedicatedDatabase(String url) {
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/harudle_block_test")) {
            throw new IllegalStateException("차단 테스트 전용 로컬 DB 주소가 필요합니다.");
        }
        int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
        if (port > 65_535) {
            throw new IllegalStateException("차단 테스트 전용 로컬 DB 주소가 필요합니다.");
        }
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    public static final class DatabaseAvailable implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            boolean available = System.getenv("HARUDLE_BLOCK_TEST_DATABASE_URL") != null
                    || DockerClientFactory.instance().isDockerAvailable();
            return available ? ConditionEvaluationResult.enabled("차단 테스트 전용 DB를 사용할 수 있습니다.")
                    : ConditionEvaluationResult.disabled("차단 테스트 전용 DB 또는 Docker가 필요합니다.");
        }
    }
}
