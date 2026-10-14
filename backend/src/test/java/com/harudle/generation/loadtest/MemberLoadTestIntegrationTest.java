package com.harudle.generation.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.HarudleApplication;
import com.harudle.generation.config.GenerationAdapterConfiguration;
import com.harudle.generation.config.StoryboardGenerationConfiguration;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import com.harudle.generation.diary.service.storyboard.StoryboardGenerationService;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@EnabledIfEnvironmentVariable(named = "HARUDLE_K6_VERIFY", matches = "smoke|peak")
@Testcontainers
@SpringBootTest(classes = MemberLoadTestIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "spring.profiles.active=", "DEPLOY_ENV=dev",
        "harudle.load-test.verification=true",
        "harudle.generation.adapters.enabled=true", "harudle.generation.storage.r2.enabled=false",
        "spring.security.oauth2.client.registration.kakao.client-id=test",
        "spring.security.oauth2.client.registration.kakao.client-secret=test",
        "app.auth.access-token.secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "logging.level.root=WARN"
})
@Import(MemberLoadTestIntegrationTest.StorageConfiguration.class)
class MemberLoadTestIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("harudle_dev");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StoryboardGenerationService normalStoryboardService;
    @Autowired private DiaryImageGenerator normalImageGenerator;

    @Test
    void executesRealHttpAuthenticationAndAllMemberJourneys() throws Exception {
        String mode = System.getenv("HARUDLE_K6_VERIFY");
        Path scripts = Path.of("load-test").toAbsolutePath();
        Path run = scripts.resolve("runs/verification-" + mode + "-" + UUID.randomUUID());
        jdbc.update("INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key) "
                + "VALUES ('test storyboard', 'test style', 'harudle/references/generation/dev/test.png')");
        UUID existingUser = UUID.randomUUID();
        UUID existingDiary = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, primary_email, name, nickname, profile_image_code, role) "
                + "VALUES (?, 'existing@example.invalid', '기존회원', '기존회원', 1, 'USER')", existingUser);
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '기존 일기')",
                existingDiary, existingUser);
        run(List.of("python3", scripts.resolve("prepare.py").toString(), "--output", run.toString(),
                "--base-url", "http://host.docker.internal:" + port), scripts.resolve("runs/prepare-verification.log"));
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(run.resolve("seed.sql")), "/tmp/seed.sql");
        POSTGRES.execInContainer("createdb", "-U", POSTGRES.getUsername(), "harudle_prod");
        for (String database : List.of("harudle_prod", "postgres")) {
            var rejected = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", database,
                    "-v", "expected_database=" + database, "-f", "/tmp/seed.sql");
            assertThat(rejected.getExitCode()).as(rejected.getStderr()).isNotZero();
            assertThat(rejected.getStderr()).contains("Load test requires harudle_dev");
        }
        var seed = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                "-f", "/tmp/seed.sql");
        assertThat(seed.getExitCode()).as(seed.getStderr()).isZero();
        String user = commandOutput("id", "-u") + ":" + commandOutput("id", "-g");
        run(List.of("docker", "run", "--rm", "--user", user, "-v", scripts + ":/scripts", "-e", "MODE=" + mode,
                "-e", "MEMBERS_FILE=/scripts/runs/" + run.getFileName() + "/members.json", "grafana/k6:2.3.0",
                "run", "--summary-export", "/scripts/runs/" + run.getFileName() + "/summary.json", "/scripts/members.js"),
                run.resolve("k6.log"));
        verifyNoInteractions(normalStoryboardService, normalImageGenerator);
        int generated = mode.equals("peak") ? 20 : 1;
        int reactions = mode.equals("peak") ? 60 : 2;
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diary_generations WHERE storyboard_model = 'fixed-30s' "
                + "AND status = 'SUCCEEDED'", Integer.class)).isEqualTo(generated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feeds", Integer.class)).isEqualTo(200 + generated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments", Integer.class)).isEqualTo(400 + reactions);
        assertThat(jdbc.queryForObject("SELECT sum(used_count) FROM daily_generation_usage", Integer.class)).isEqualTo(generated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feeds f WHERE f.like_count <> "
                + "(SELECT count(*) FROM feed_likes l WHERE l.feed_id = f.id) OR f.comment_count <> "
                + "(SELECT count(*) FROM comments c WHERE c.feed_id = f.id AND c.deleted_at IS NULL)", Integer.class)).isZero();
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(run.resolve("cleanup.sql")), "/tmp/cleanup.sql");
        var rejectedCleanup = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", "harudle_prod",
                "-v", "expected_database=harudle_prod", "-f", "/tmp/cleanup.sql");
        assertThat(rejectedCleanup.getExitCode()).as(rejectedCleanup.getStderr()).isNotZero();
        assertThat(rejectedCleanup.getStderr()).contains("Load test requires harudle_dev");
        var cleanup = POSTGRES.execInContainer("sh", "-c", "cd /tmp && psql -U " + POSTGRES.getUsername()
                + " -d " + POSTGRES.getDatabaseName() + " -f cleanup.sql");
        assertThat(cleanup.getExitCode()).as(cleanup.getStderr()).isZero();
        assertThat(jdbc.queryForObject("SELECT id FROM users", UUID.class)).isEqualTo(existingUser);
        assertThat(jdbc.queryForObject("SELECT id FROM diaries", UUID.class)).isEqualTo(existingDiary);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feeds", Integer.class)).isZero();
        var imageKeys = POSTGRES.execInContainer("cat", "/tmp/cleanup-image-keys.txt");
        assertThat(imageKeys.getExitCode()).isZero();
        assertThat(imageKeys.getStdout().lines().count()).isEqualTo(400 + generated);
        assertThat(imageKeys.getStdout()).contains("/image.webp");
    }

    private String commandOutput(String... command) throws Exception {
        Process process = new ProcessBuilder(command).start();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return new String(process.getInputStream().readAllBytes()).strip();
    }

    private void run(List<String> command, Path log) throws Exception {
        Files.createDirectories(log.getParent());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(12, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("Verification timed out: " + log);
        }
        assertThat(process.exitValue()).as(Files.readString(log)).isZero();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "harudle.load-test.verification", havingValue = "true")
    @EnableAutoConfiguration
    @AutoConfigurationPackage(basePackages = "com.harudle")
    @ComponentScan(basePackages = "com.harudle", excludeFilters = {
            @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                    HarudleApplication.class, GenerationAdapterConfiguration.class, StoryboardGenerationConfiguration.class
            })
    })
    static class TestApplication {}

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {
        @Bean
        StoryboardGenerationService storyboardGenerationService() {
            return mock(StoryboardGenerationService.class);
        }

        @Bean
        DiaryImageGenerator diaryImageGenerator() {
            return mock(DiaryImageGenerator.class);
        }

        @Bean
        ImageStorage imageStorage() {
            ImageStorage storage = mock(ImageStorage.class);
            when(storage.load(anyString())).thenReturn(new ReferenceImage(new ByteArrayResource(new byte[]{1}), MediaType.IMAGE_PNG));
            when(storage.store(any(), any())).thenAnswer(call ->
                    "harudle/generated/diary-images/dev/" + call.getArgument(0) + "/" + UUID.randomUUID() + "/image.webp");
            return storage;
        }

        @Bean
        @Primary
        ImageUrlProvider testImageUrlProvider() {
            return key -> new ImageAccessUrl(URI.create("https://images.invalid/" + key), Instant.now().plusSeconds(900));
        }
    }
}
