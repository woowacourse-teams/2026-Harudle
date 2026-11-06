package com.harudle.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.harudle.TestcontainersConfiguration;
import com.harudle.auth.application.RefreshTokenService;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.diary.domain.Diary;
import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.generation.diary.domain.*;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.generation.diary.service.ImageRecoveryService;
import com.harudle.generation.diary.service.port.*;
import com.harudle.generation.diary.service.port.dto.*;
import com.harudle.generation.prompt.domain.GenerationPrompt;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import com.harudle.support.DiarySourceTextTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = {ImageRecoveryApplication.class, ImageRecoveryApplicationTest.DatabaseConfiguration.class},
        properties = {"DB_PASSWORD=test", "app.auth.frontend-origins[0]=https://dev.harudle.com",
                "app.auth.access-token.issuer=harudle", "app.auth.access-token.audience=harudle-api",
                "app.auth.access-token.secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "app.auth.access-token.ttl=10m", "app.auth.refresh-token.cookie-name=harudle_refresh",
                "app.auth.refresh-token.cookie-path=/api/v1/auth", "app.auth.refresh-token.secure=false",
                "app.auth.refresh-token.same-site=Lax", "app.auth.refresh-token.ttl=14d",
                "harudle.diary.source-text-encryption.keys.primary=",
                "harudle.diary.source-text-encryption.verification-file=",
                "harudle.generation.adapters.enabled=false", "harudle.generation.storage.r2.enabled=false",
                "harudle.generation.worker.poll-interval=1ms", "harudle.generation.lifecycle.cleanup-interval=1ms",
                "harudle.admin.image-recovery.interval=0s", "harudle.legal.require-terms-acceptance=false"})
@ActiveProfiles({"prod", "image-recovery"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ImageRecoveryApplicationTest {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired DiaryGenerationRepository generations;
    @Autowired GenerationPromptRepository prompts;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JwtEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired RefreshTokenService refreshTokens;
    @MockitoBean ImageStorage storage;
    @MockitoBean DiaryImageGenerator generator;
    @MockitoBean ImageRecoveryService recovery;
    private User admin;
    private GenerationPrompt prompt;
    private DiaryGeneration success;
    private String accessToken;

    @BeforeEach
    void setUp() {
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            admin = users.saveAndFlush(new User("recovery-" + UUID.randomUUID() + "@example.com", "복구 담당자", Instant.now()));
            jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?", admin.getId());
            prompt = prompts.saveAndFlush(new GenerationPrompt("story", "style", "references/image.png"));
            for (int index = 0; index < 3; index++) {
                Diary diary = Diary.create(admin.getId(), LocalDate.of(2026, 11, 1).plusDays(index),
                        "암호화된 복구 시험 원문", DiarySourceTextTestSupport.cipher());
                entityManager.persist(diary);
                entityManager.flush();
                var generation = index == 0
                        ? DiaryGeneration.start(diary.getId(), prompt.getId(), UUID.randomUUID(), "a".repeat(64))
                        : DiaryGeneration.pending(diary.getId(), prompt.getId(), UUID.randomUUID(), "a".repeat(64), LocalDate.now());
                if (index == 0) {
                    generation.succeed(storyboard(), "harudle/generated/diary-images/dev/image.png", Instant.EPOCH);
                    success = generation;
                } else if (index == 2) generation.startProcessing();
                generations.saveAndFlush(generation);
            }
            jdbc.update("INSERT INTO daily_generation_usage(user_id,usage_date,used_count,limit_count) VALUES (?,CURRENT_DATE,2,3)", admin.getId());
            jdbc.update("UPDATE diary_generations SET created_at='2020-01-01',updated_at='2020-01-01' "
                    + "WHERE diary_id IN (SELECT id FROM diaries WHERE user_id=?) AND status IN ('PENDING','PROCESSING')", admin.getId());
        });
        var claims = JwtClaimsSet.builder().issuer("harudle").audience(List.of("harudle-api"))
                .subject(admin.getId().toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        accessToken = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @AfterEach
    void cleanUp() {
        if (admin == null) return;
        jdbc.update("DELETE FROM diary_generations WHERE diary_id IN (SELECT id FROM diaries WHERE user_id=?)", admin.getId());
        jdbc.update("DELETE FROM users WHERE id=?", admin.getId());
        if (prompt != null) prompts.deleteById(prompt.getId());
    }

    @Test
    void actualKeylessBootstrapRestoresImageWithoutChangingDiaryGenerationOrUsage() throws Exception {
        assertThat(context.getBeansOfType(DiarySourceTextCipher.class)).isEmpty();
        assertThat(context.getBeansOfType(DiaryGenerationWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.harudle.HarudleApplication.class)).isEmpty();
        assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
        var before = databaseState();
        var image = new GeneratedImage(new ByteArrayResource(new byte[]{1}), MediaType.IMAGE_PNG);
        when(storage.load("references/image.png")).thenReturn(new ReferenceImage(new ByteArrayResource(new byte[]{2}), MediaType.IMAGE_PNG));
        when(generator.generate(any())).thenReturn(image);
        when(storage.restoreIfMissing(success.getImageObjectKey(), image)).thenReturn(true);
        var csrf = csrf();
        mvc.perform(post("/api/v1/admin/generations/" + success.getId() + "/restore-image")
                        .header("Authorization", "Bearer " + accessToken).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESTORED"));
        mvc.perform(get("/api/v1/diaries/" + success.getDiaryId()).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
        assertThat(databaseState()).isEqualTo(before);
        verify(generator).generate(any());
    }

    @Test
    void expiredAccessCanBeReplacedUsingExistingRefreshCookieAndCsrf() throws Exception {
        var issued = refreshTokens.issue(admin, Instant.now());
        var csrf = csrf();
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("harudle_refresh", issued.rawToken()), csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("harudle_refresh", issued.rawToken())))
                .andExpect(status().isForbidden());
    }

    private List<List<Map<String, Object>>> databaseState() {
        return List.of(jdbc.queryForList("SELECT * FROM diaries ORDER BY id"),
                jdbc.queryForList("SELECT * FROM diary_generations ORDER BY id"),
                jdbc.queryForList("SELECT * FROM daily_generation_usage ORDER BY user_id,usage_date"));
    }

    private Csrf csrf() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        return new Csrf(Arrays.stream(response.getCookies()).filter(c -> "XSRF-TOKEN".equals(c.getName())
                && "/api/v1".equals(c.getPath())).findFirst().orElseThrow(), mapper.readTree(response.getContentAsString()).get("token").asString());
    }
    private record Csrf(Cookie cookie, String value) { }
    private static Storyboard storyboard() {
        return new Storyboard("하루", "주인공", java.util.stream.IntStream.rangeClosed(1, 4)
                .mapToObj(n -> new StoryPanel(n, "장면 " + n, "공원", "주인공", "기쁨", List.of())).toList());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean InitializingBean recoveryTestSchema(DataSource dataSource) {
            // Test fixture only: production recovery never registers a migration initializer.
            // CONCURRENTLY indexes must not wait on Flyway's own transaction-scoped advisory lock.
            // Match the application's migration policy and bound fixture setup as well.
            return () -> Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
                    .group(false)
                    .initSql("SET lock_timeout = '5s'; SET statement_timeout = '60s';")
                    .load().migrate();
        }
        @Bean static BeanFactoryPostProcessor initializeTestSchemaBeforeValidation() {
            return beans -> beans.getBeanDefinition("entityManagerFactory").setDependsOn("recoveryTestSchema");
        }
    }
}
