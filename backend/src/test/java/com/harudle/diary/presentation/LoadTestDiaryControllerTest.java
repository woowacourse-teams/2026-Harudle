package com.harudle.diary.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.config.TimeConfiguration;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.diary.service.LoadTestDiaryCreationService;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.generation.usage.domain.GenerationUsage;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = LoadTestDiaryController.class, properties = "DEPLOY_ENV=dev")
@Import({AuthenticatedUserIdResolver.class, DiaryResponseAssembler.class, TimeConfiguration.class,
        ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class, TraceIdConfiguration.class,
        CsrfConfiguration.class, SecurityConfig.class})
class LoadTestDiaryControllerTest {

    private static final String PATH = "/api/v1/load-test/diaries";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DIARY_ID = UUID.randomUUID();
    private static final UUID KEY = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 10, 14);
    private static final String BODY = """
            {"diaryDate":"2026-10-14","sourceText":"부하 테스트 일기"}
            """;

    @Autowired private MockMvc mvc;
    @MockitoBean private LoadTestDiaryCreationService creationService;
    @MockitoBean private ImageUrlProvider imageUrlProvider;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private OAuthLoginSuccessHandler successHandler;
    @MockitoBean private OAuthLoginFailureHandler failureHandler;
    @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

    @BeforeEach
    void configureBearerToken() {
        when(jwtDecoder.decode("test-token")).thenReturn(Jwt.withTokenValue("test-token")
                .header("alg", "RS256").subject(USER_ID.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/load-test/ai-stub")).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(creationService);
    }

    @Test
    void reportsStubWithoutLoadTestProfile() throws Exception {
        mvc.perform(get("/api/v1/load-test/ai-stub").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delayMs").value(30000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", ""})
    void isAbsentOutsideDev(String environment) {
        new ApplicationContextRunner().withUserConfiguration(LoadTestDiaryController.class)
                .withPropertyValues("DEPLOY_ENV=" + environment)
                .run(application -> assertThat(application).hasNotFailed()
                        .doesNotHaveBean(LoadTestDiaryController.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void returnsSavedDiaryOrReusesIdempotentResult(boolean newlyCreated) throws Exception {
        Instant now = Instant.now();
        when(creationService.create(any())).thenReturn(new CreateDiaryResult(
                DIARY_ID, DATE, "부하 테스트 일기", now,
                new DiaryGenerationResult(UUID.randomUUID(), GenerationStatus.SUCCEEDED, "부하 테스트 일기", "image-key", now),
                new GenerationUsage(DATE, 1, 3), newlyCreated));
        when(imageUrlProvider.createAccessUrl("image-key"))
                .thenReturn(new ImageAccessUrl(URI.create("https://images.invalid/diary.webp"), now.plusSeconds(900)));
        var response = mvc.perform(post(PATH)
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(newlyCreated ? 201 : 200))
                .andExpect(jsonPath("$.id").value(DIARY_ID.toString()))
                .andExpect(jsonPath("$.generation.status").value("SUCCEEDED"));
        if (newlyCreated) {
            response.andExpect(header().string("Location", "/api/v1/diaries/" + DIARY_ID));
        }
        verify(creationService).create(new CreateDiaryCommand(USER_ID, DATE, "부하 테스트 일기", KEY));
    }

    @Test
    void rejectsMissingIdempotencyKey() throws Exception {
        mvc.perform(post(PATH)
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(creationService);
    }
}
