package com.harudle.generation.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.HarudleApplication;
import com.harudle.generation.config.GenerationAdapterConfiguration;
import com.harudle.generation.config.StoryboardGenerationConfiguration;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
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
import org.mockito.ArgumentCaptor;
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
import org.springframework.core.io.ClassPathResource;
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
        "harudle.generation.worker.poll-interval=100ms",
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
        // 전체 행동 검증에서는 한 번에 생성하는 인원만큼 실행한다. 운영 설정과 별개다.
        registry.add("harudle.generation.worker.concurrency",
                () -> "peak".equals(System.getenv("HARUDLE_K6_VERIFY")) ? 20 : 1);
    }

    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StoryboardGenerationService normalStoryboardService;
    @Autowired private DiaryImageGenerator normalImageGenerator;
    @Autowired private ImageStorage imageStorage;

    @Test
    void executesRealHttpAuthenticationAndAllMemberJourneys() throws Exception {
        String mode = System.getenv("HARUDLE_K6_VERIFY");
        Path scripts = Path.of("load-test").toAbsolutePath();
        Path run = scripts.resolve("runs/verification-" + mode + "-" + UUID.randomUUID());
        jdbc.update("INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key) "
                + "VALUES ('test storyboard', 'test style', 'harudle/references/generation/dev/test.png')");
        UUID existingUser = UUID.randomUUID();
        UUID existingDiary = UUID.randomUUID();
        UUID existingFeed = UUID.randomUUID();
        UUID existingGeneration = UUID.randomUUID();
        String existingImage = "harudle/generated/diary-images/dev/" + existingGeneration + "/" + UUID.randomUUID() + "/image.png";
        jdbc.update("INSERT INTO users (id, primary_email, name, nickname, profile_image_code, role) "
                + "VALUES (?, 'existing@example.invalid', '기존회원', '기존회원', 1, 'USER')", existingUser);
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '기존 일기')",
                existingDiary, existingUser);
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id, like_count, comment_count) "
                + "VALUES (?, ?, (SELECT min(id) FROM categories), 1, 1)", existingFeed, existingDiary);
        jdbc.update("INSERT INTO comments (id, feed_id, author_id, content) VALUES (?, ?, ?, '기존 댓글')",
                UUID.randomUUID(), existingFeed, existingUser);
        jdbc.update("INSERT INTO feed_likes (id, feed_id, user_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), existingFeed, existingUser);
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
        jdbc.update("INSERT INTO diary_generations (id, diary_id, prompt_id, idempotency_key, request_fingerprint, "
                + "status, storyboard, title, image_object_key, completed_at, usage_date, storyboard_provider, storyboard_model) "
                + "SELECT ?, ?, prompt_id, ?, request_fingerprint, status, storyboard, '기존 일기', ?, completed_at, "
                + "usage_date, storyboard_provider, 'existing' FROM diary_generations LIMIT 1",
                existingGeneration, existingDiary, UUID.randomUUID(), existingImage);
        String user = commandOutput("id", "-u") + ":" + commandOutput("id", "-g");
        run(List.of("docker", "run", "--rm", "--user", user, "-v", scripts + ":/scripts", "-e", "MODE=" + mode,
                "-e", "MEMBERS_FILE=/scripts/runs/" + run.getFileName() + "/members.json", "grafana/k6:2.3.0",
                "run", "--summary-export", "/scripts/runs/" + run.getFileName() + "/summary.json", "/scripts/members.js"),
                run.resolve("k6.log"));
        verifyNoInteractions(normalStoryboardService, normalImageGenerator);
        int generated = mode.equals("peak") ? 20 : 1;
        int reactions = mode.equals("peak") ? 60 : 2;
        var savedImages = ArgumentCaptor.forClass(GeneratedImage.class);
        verify(imageStorage, times(generated)).store(any(), savedImages.capture());
        byte[] fixedImage = new ClassPathResource("load-test-diary.png").getContentAsByteArray();
        for (GeneratedImage image : savedImages.getAllValues()) {
            assertThat(image.mediaType()).isEqualTo(MediaType.IMAGE_PNG);
            assertThat(image.resource().getContentAsByteArray()).isEqualTo(fixedImage);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diary_generations WHERE storyboard_model = 'fixed-30s' "
                + "AND status = 'SUCCEEDED'", Integer.class)).isEqualTo(generated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feeds", Integer.class)).isEqualTo(201 + generated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments", Integer.class)).isEqualTo(401 + reactions);
        assertThat(jdbc.queryForObject("SELECT sum(used_count) FROM daily_generation_usage", Integer.class)).isEqualTo(generated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feeds f WHERE f.like_count <> "
                + "(SELECT count(*) FROM feed_likes l WHERE l.feed_id = f.id) OR f.comment_count <> "
                + "(SELECT count(*) FROM comments c WHERE c.feed_id = f.id AND c.deleted_at IS NULL)", Integer.class)).isZero();
        prepareCleanupCommands(run);
        runCleanup(scripts, run, "harudle_prod", false);
        assertThat(Files.readString(run.resolve("cleanup-verification.log"))).contains("Load test requires harudle_dev");
        UUID lockedUser = jdbc.queryForObject("SELECT id FROM users WHERE primary_email LIKE '%.loadtest.invalid' LIMIT 1", UUID.class);
        UUID busyDiary = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '생성 중')",
                busyDiary, lockedUser);
        jdbc.update("INSERT INTO diary_generations (id, diary_id, prompt_id, idempotency_key, request_fingerprint, status, usage_date) "
                + "SELECT ?, ?, prompt_id, ?, request_fingerprint, 'PROCESSING', usage_date FROM diary_generations LIMIT 1",
                UUID.randomUUID(), busyDiary, UUID.randomUUID());
        runCleanup(scripts, run, "harudle_dev", false);
        assertThat(Files.readString(run.resolve("cleanup-verification.log"))).contains("Generation is still processing");
        assertThat(Files.exists(run.resolve("aws-calls.jsonl"))).isFalse();
        jdbc.update("DELETE FROM diaries WHERE id = ?", busyDiary);
        try (var connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, lockedUser);
                statement.executeQuery();
                runCleanup(scripts, run, "harudle_dev", false);
                assertThat(Files.readString(run.resolve("cleanup-verification.log"))).contains("lock timeout");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(201);
                assertThat(Files.exists(run.resolve("aws-calls.jsonl"))).isFalse();
            } finally {
                connection.rollback();
            }
        }
        Files.writeString(run.resolve("fail-s3"), "fail delete requests");
        runCleanup(scripts, run, "harudle_dev", false);
        assertThat(Files.readString(run.resolve("cleanup-verification.log"))).contains("DB cleanup committed");
        assertThat(jdbc.queryForObject("SELECT id FROM users", UUID.class)).isEqualTo(existingUser);
        assertThat(jdbc.queryForObject("SELECT id FROM diaries", UUID.class)).isEqualTo(existingDiary);
        assertThat(jdbc.queryForObject("SELECT id FROM feeds", UUID.class)).isEqualTo(existingFeed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_likes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT image_object_key FROM diary_generations", String.class)).isEqualTo(existingImage);
        String plan = Files.readString(run.resolve("cleanup.json"));
        assertThat(plan.lines().filter(line -> line.contains("\"imageKey\"")).count()).isEqualTo(401 + generated);
        assertThat(plan).contains("/image.webp").doesNotContain(existingImage);
        Files.delete(run.resolve("fail-s3"));
        runCleanup(scripts, run, "harudle_dev", true);
        assertThat(Files.readString(run.resolve("cleanup.json"))).isEqualTo(plan);
        assertThat(Files.readString(run.resolve("aws-calls.jsonl"))).doesNotContain(existingImage).doesNotContain("/prod/");
    }

    private void prepareCleanupCommands(Path run) throws Exception {
        Path bin = Files.createDirectory(run.resolve("bin"));
        Path psql = bin.resolve("psql");
        Files.writeString(psql, """
                #!/bin/sh
                exec docker exec -i %s psql -U %s -d "$PGDATABASE" "$@"
                """.formatted(POSTGRES.getContainerId(), POSTGRES.getUsername()));
        assertThat(psql.toFile().setExecutable(true)).isTrue();
        Files.writeString(run.resolve("cleanup-verification.py"), """
                import json
                from pathlib import Path
                import sys
                import threading
                from unittest.mock import patch
                sys.path.insert(0, sys.argv[1])
                import assets
                import cleanup
                directory = Path(__file__).resolve().parent
                lock = threading.Lock()
                def fake_aws(*args):
                    with lock:
                        with (directory / 'aws-calls.jsonl').open('a') as output:
                            output.write(json.dumps(args) + '\\n')
                    plan = json.loads((directory / 'cleanup.json').read_text())
                    if args[0] == 'list-objects-v2':
                        prefix = args[args.index('--prefix') + 1]
                        return {'Contents': [{'Key': r['imageKey']} for r in plan['generations']
                                if r['imageKey'] and r['imageKey'].startswith(prefix)]}
                    if args[0] == 'head-object':
                        return {'Metadata': {'load-test-run': plan['runId']}}
                    if args[0] == 'delete-objects':
                        return {'Errors': [{'Code': 'AccessDenied'}]} if (directory / 'fail-s3').exists() else {}
                    raise AssertionError('Unexpected AWS operation')
                with patch.object(assets, 'aws', side_effect=fake_aws):
                    cleanup.cleanup(directory)
                """);
    }

    private void runCleanup(Path scripts, Path directory, String database, boolean succeeds) throws Exception {
        var builder = new ProcessBuilder("python3", directory.resolve("cleanup-verification.py").toString(), scripts.toString());
        builder.environment().put("PATH", directory.resolve("bin") + ":" + System.getenv("PATH"));
        builder.environment().put("PGDATABASE", database);
        Path log = directory.resolve("cleanup-verification.log");
        Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("Cleanup verification timed out: " + log);
        }
        assertThat(process.exitValue() == 0).as(Files.readString(log)).isEqualTo(succeeds);
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
