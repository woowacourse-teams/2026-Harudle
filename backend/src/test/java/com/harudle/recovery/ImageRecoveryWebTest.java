package com.harudle.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.harudle.admin.presentation.AdminImageRecoveryController;
import com.harudle.admin.presentation.AdminR2ImageRecoveryController;
import com.harudle.admin.service.AdminImageRecoveryService;
import com.harudle.admin.service.AdminR2ImageRecoveryService;
import com.harudle.admin.service.RecoveryExecutionGate;
import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.application.port.AccountRestrictionReader;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.common.error.ImageRecoveryErrorConfiguration;
import com.harudle.common.security.ApiCorsConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.JwtTokenConfiguration;
import com.harudle.common.security.LegacyCsrfCookieCleaner;
import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.legal.application.TermsAcceptanceService;
import com.harudle.legal.application.TermsAcceptanceRequiredException;
import com.harudle.generation.diary.domain.*;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.generation.diary.service.ImageRecoveryService;
import com.harudle.generation.diary.service.dto.ImageRecoveryResult;
import com.harudle.generation.diary.service.port.*;
import com.harudle.generation.diary.service.port.dto.*;
import com.harudle.generation.prompt.domain.GenerationPrompt;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ImageRecoveryWebTest {
    private static final UUID ADMIN = UUID.randomUUID();
    private static final String KEY = "harudle/generated/diary-images/dev/image.png";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jKp8AAAAASUVORK5CYII=");
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private String token;
    private Cookie csrfCookie;
    private String csrfToken;
    private DiaryGeneration generation;

    @BeforeEach
    void setUp() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("image-recovery");
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "test", Map.of("app.auth.frontend-origins[0]", "https://dev.harudle.com",
                        "app.auth.access-token.issuer", "harudle", "app.auth.access-token.audience", "harudle-api",
                        "app.auth.access-token.secret-base64", Base64.getEncoder().encodeToString(new byte[32]),
                        "app.auth.access-token.ttl", "10m", "harudle.admin.image-recovery.interval", "0s")));
        context.register(TestConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        User admin = mock(User.class);
        when(admin.isAdmin()).thenReturn(true);
        when(context.getBean(UserRepository.class).findById(ADMIN)).thenReturn(Optional.of(admin));
        var claims = JwtClaimsSet.builder().issuer("harudle").audience(List.of("harudle-api"))
                .subject(ADMIN.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        token = context.getBean(JwtEncoder.class).encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        var issued = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        csrfCookie = Arrays.stream(issued.getCookies()).filter(c -> "XSRF-TOKEN".equals(c.getName())
                && "/api/v1".equals(c.getPath())).findFirst().orElseThrow();
        csrfToken = context.getBean(ObjectMapper.class).readTree(issued.getContentAsString()).get("token").asString();
        generation = DiaryGeneration.start(UUID.randomUUID(), 1L, UUID.randomUUID(), "a".repeat(64));
        generation.succeed(storyboard(), KEY, Instant.EPOCH);
        when(context.getBean(DiaryGenerationRepository.class).findById(generation.getId()))
                .thenReturn(Optional.of(generation));
    }

    @AfterEach
    void tearDown() { context.close(); }

    @Test
    void startsWithoutDiaryCipherOrWorkerAndIssuesActualCsrfToken() {
        assertThat(context.getBeansOfType(DiarySourceTextCipher.class)).isEmpty();
        assertThat(context.getBeansOfType(DiaryGenerationWorker.class)).isEmpty();
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfToken).isNotBlank();
    }

    @Test
    void administratorCanRestoreR2BackupWithoutReadingDiary() throws Exception {
        ImageRecoveryService recovery = context.getBean(ImageRecoveryService.class);
        when(recovery.recover(KEY, false)).thenReturn(new ImageRecoveryResult("RESTORED", "dev", "s3", "r2",
                KEY, KEY, "image/png", PNG.length, "sha", List.of(KEY), true, false, Instant.EPOCH));
        mvc.perform(authorized(post("/api/v1/admin/generations/restore-image/r2"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"environment\":\"dev\",\"generationIds\":[\""
                                + generation.getId() + "\"],\"dryRun\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].status").value("RESTORED"));
        assertGenerationUnchanged();
        verify(context.getBean(DiaryGenerationRepository.class)).findById(generation.getId());
        verifyNoMoreInteractions(context.getBean(DiaryGenerationRepository.class));
    }

    @Test
    void administratorCanUploadMissingImage() throws Exception {
        when(context.getBean(ImageStorage.class).restoreIfMissing(eq(KEY), any())).thenReturn(true);
        mvc.perform(authorized(multipart("/api/v1/admin/generations/restore-image/upload")
                        .file(new MockMultipartFile("image", "image.png", "image/png", PNG)).param("imageObjectKey", KEY)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESTORED"));
        verifyNoInteractions(context.getBean(DiaryGenerationRepository.class));
    }

    @Test
    void administratorCanRegenerateFromSavedStoryboardWithoutSourceText() throws Exception {
        var prompts = context.getBean(GenerationPromptRepository.class);
        when(prompts.findFirstByOrderByIdDesc()).thenReturn(Optional.of(new GenerationPrompt("story", "style", "ref")));
        when(context.getBean(ImageStorage.class).load("ref"))
                .thenReturn(new ReferenceImage(new ByteArrayResource(PNG), MediaType.IMAGE_PNG));
        var image = new GeneratedImage(new ByteArrayResource(PNG), MediaType.IMAGE_PNG);
        when(context.getBean(DiaryImageGenerator.class).generate(any())).thenReturn(image);
        when(context.getBean(ImageStorage.class).restoreIfMissing(KEY, image)).thenReturn(true);
        mvc.perform(authorized(post("/api/v1/admin/generations/" + generation.getId() + "/restore-image")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESTORED"));
        assertGenerationUnchanged();
        verify(context.getBean(DiaryGenerationRepository.class)).findById(generation.getId());
        verifyNoMoreInteractions(context.getBean(DiaryGenerationRepository.class));
    }

    @Test
    void anonymousAndNonAdminCannotRecover() throws Exception {
        mvc.perform(post("/api/v1/admin/generations/" + generation.getId() + "/restore-image")
                        .cookie(csrfCookie).header("X-XSRF-TOKEN", csrfToken)).andExpect(status().isUnauthorized());
        when(context.getBean(UserRepository.class).findById(ADMIN)).thenReturn(Optional.of(mock(User.class)));
        mvc.perform(authorized(post("/api/v1/admin/generations/" + generation.getId() + "/restore-image")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(DiaryGenerationRepository.class), context.getBean(ImageStorage.class));
    }

    @Test
    void suspendedAdminCannotRecover() throws Exception {
        when(context.getBean(AccountRestrictionReader.class).isSuspendedForRequest(ADMIN)).thenReturn(true);
        mvc.perform(authorized(post("/api/v1/admin/generations/" + generation.getId() + "/restore-image")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(DiaryGenerationRepository.class));
    }

    @Test
    void requiredTermsAcceptanceStillApplies() throws Exception {
        doThrow(new TermsAcceptanceRequiredException()).when(context.getBean(TermsAcceptanceService.class)).requireAccepted(ADMIN);
        mvc.perform(authorized(post("/api/v1/admin/generations/" + generation.getId() + "/restore-image")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TERMS_ACCEPTANCE_REQUIRED"));
        verifyNoInteractions(context.getBean(DiaryGenerationRepository.class));
    }

    @Test
    void bearerTokenDoesNotBypassCsrf() throws Exception {
        mvc.perform(post("/api/v1/admin/generations/" + generation.getId() + "/restore-image")
                        .header("Authorization", "Bearer " + token)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        verifyNoInteractions(context.getBean(DiaryGenerationRepository.class));
    }

    @Test
    void sourceTextAndOtherAdminEndpointsAreDenied() throws Exception {
        for (String path : List.of("/api/v1/diaries/" + UUID.randomUUID(), "/api/v1/admin/generations",
                "/api/v1/feeds", "/v3/api-docs")) {
            mvc.perform(authorized(get(path))).andExpect(status().isForbidden());
        }
        mvc.perform(authorized(post("/api/v1/diaries")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceText\":\"secret\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(DiaryGenerationRepository.class), context.getBean(ImageStorage.class));
    }

    @Test
    void normalComponentScanDoesNotPickUpMaintenanceBootstrap() {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        scanner.setEnvironment(new MockEnvironment());
        assertThat(scanner.findCandidateComponents("com.harudle.recovery")).allMatch(bean -> bean.getBeanClassName().contains("Test$"));
    }

    @Test
    void schemaChangesAreRejected() {
        assertThatThrownBy(() -> ImageRecoveryApplication.recoverySchemaPolicy(
                new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "create"))
                .postProcessBeanFactory(new org.springframework.beans.factory.support.DefaultListableBeanFactory()))
                .isInstanceOf(IllegalStateException.class);
    }

    private <T extends AbstractMockHttpServletRequestBuilder<T>> T authorized(T request) {
        return request.header("Authorization", "Bearer " + token).cookie(csrfCookie).header("X-XSRF-TOKEN", csrfToken);
    }

    private void assertGenerationUnchanged() {
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(generation.getImageObjectKey()).isEqualTo(KEY);
        assertThat(generation.getCompletedAt()).isEqualTo(Instant.EPOCH);
        assertThat(generation.hasSameRequestFingerprint("a".repeat(64))).isTrue();
    }

    private static Storyboard storyboard() {
        return new Storyboard("하루", "주인공", java.util.stream.IntStream.rangeClosed(1, 4)
                .mapToObj(n -> new StoryPanel(n, "장면 " + n, "공원", "주인공", "기쁨", List.of())).toList());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({ImageRecoverySecurityConfiguration.class, ImageRecoveryCsrfController.class,
            JwtTokenConfiguration.class, CsrfConfiguration.class, LegacyCsrfCookieCleaner.class, ApiCorsConfiguration.class,
            ImageRecoveryErrorConfiguration.class, AdminImageRecoveryController.class,
            AdminR2ImageRecoveryController.class, AdminImageRecoveryService.class,
            AdminR2ImageRecoveryService.class, AccountAccessPolicy.class})
    static class TestConfiguration {
        @Bean RecoveryExecutionGate gate() { return new RecoveryExecutionGate(Duration.ZERO); }
        @Bean TermsAcceptanceService terms() { return mock(TermsAcceptanceService.class); }
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean DiaryGenerationRepository generations() { return mock(DiaryGenerationRepository.class); }
        @Bean GenerationPromptRepository prompts() { return mock(GenerationPromptRepository.class); }
        @Bean ImageStorage storage() { return mock(ImageStorage.class); }
        @Bean DiaryImageGenerator generator() { return mock(DiaryImageGenerator.class); }
        @Bean AccountRestrictionReader restrictions() { return mock(AccountRestrictionReader.class); }
        @Bean ImageRecoveryService recovery() {
            var service = mock(ImageRecoveryService.class);
            when(service.environment()).thenReturn("dev");
            return service;
        }
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().findAndAddModules()
                .addMixIn(org.springframework.http.ProblemDetail.class, org.springframework.http.converter.json.ProblemDetailJacksonMixin.class).build(); }
    }
}
