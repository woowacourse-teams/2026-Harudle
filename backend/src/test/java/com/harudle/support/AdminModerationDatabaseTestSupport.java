package com.harudle.support;

import com.harudle.HarudleApplication;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Only an explicitly selected, dedicated loopback database or an isolated Testcontainers database is used. */
@SpringBootTest(classes = HarudleApplication.class, properties = {
        "app.auth.access-token.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.security.oauth2.client.registration.kakao.client-id=moderation-test-client",
        "spring.security.oauth2.client.registration.kakao.client-secret=moderation-test-secret",
        "harudle.generation.adapters.enabled=false",
        "harudle.generation.prompt-bootstrap.enabled=false",
        "harudle.generation.storage.r2.enabled=false",
        "harudle.push.fcm.enabled=false"
})
@ExtendWith(AdminModerationDatabaseTestSupport.DatabaseAvailable.class)
public abstract class AdminModerationDatabaseTestSupport {

    private static PostgreSQLContainer postgres;

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @MockitoBean(enforceOverride = true)
    private DiaryGenerationWorker generationWorker;

    @DynamicPropertySource
    static synchronized void databaseProperties(DynamicPropertyRegistry properties) {
        String url = System.getenv("HARUDLE_REPORT_TEST_DATABASE_URL");
        if (url != null) {
            requireDedicatedLocalDatabase(url);
            configure(properties, url,
                    environmentOrDefault("HARUDLE_REPORT_TEST_DATABASE_USERNAME", "harudle_report_test"),
                    environmentOrDefault("HARUDLE_REPORT_TEST_DATABASE_PASSWORD", ""));
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

    private static void requireDedicatedLocalDatabase(String url) {
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/harudle_report_test")) {
            throw new IllegalStateException("관리자 심사 테스트 전용 로컬 DB 주소가 필요합니다.");
        }
        int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
        if (port > 65_535) {
            throw new IllegalStateException("관리자 심사 테스트 전용 로컬 DB 주소가 필요합니다.");
        }
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    public static class DatabaseAvailable implements ExecutionCondition {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (System.getenv("HARUDLE_REPORT_TEST_DATABASE_URL") != null
                    || DockerClientFactory.instance().isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Isolated PostgreSQL is available");
            }
            return ConditionEvaluationResult.disabled("No explicitly selected test PostgreSQL or Docker is available");
        }
    }
}
