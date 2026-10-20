package com.harudle.diary.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.domain.User;
import com.harudle.auth.domain.UserRole;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

@WebMvcTest(controllers = LoadTestDiaryController.class,
        properties = {"DEPLOY_ENV=dev", "harudle.generation.adapters.enabled=true"})
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
        User user = mock(User.class);
        when(user.getPrimaryEmail()).thenReturn("0@0123456789abcdef0123456789abcdef.loadtest.invalid");
        when(user.getRole()).thenReturn(UserRole.USER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(jwtDecoder.decode("test-token")).thenReturn(Jwt.withTokenValue("test-token")
                .header("alg", "RS256").subject(USER_ID.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build());
    }

    @Test
    void ordinaryDevMembersCannotCreateStubDiaries() throws Exception {
        User user = mock(User.class);
        when(user.getPrimaryEmail()).thenReturn("member@example.com");
        when(user.getRole()).thenReturn(UserRole.USER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        mvc.perform(post(PATH).header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(userRepository).findById(USER_ID);
        verifyNoInteractions(creationService);
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
                .andExpect(jsonPath("$.delayMs").value(30000))
                .andExpect(jsonPath("$.output").value("test-image"))
                .andExpect(jsonPath("$.execution").value("db-queue"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", ""})
    void isAbsentOutsideDev(String environment) {
        new ApplicationContextRunner().withUserConfiguration(LoadTestDiaryController.class)
                .withPropertyValues("DEPLOY_ENV=" + environment, "harudle.generation.adapters.enabled=true")
                .run(application -> assertThat(application).hasNotFailed()
                        .doesNotHaveBean(LoadTestDiaryController.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", ""})
    void isAbsentInDevWhenAdaptersAreDisabledOrUnset(String enabled) {
        new ApplicationContextRunner().withUserConfiguration(LoadTestDiaryController.class)
                .withPropertyValues("DEPLOY_ENV=dev", "harudle.generation.adapters.enabled=" + enabled)
                .run(application -> assertThat(application).hasNotFailed()
                        .doesNotHaveBean(LoadTestDiaryController.class));
    }

    @ParameterizedTest
    @CsvSource({"true,PENDING,202", "false,PENDING,200", "false,PROCESSING,200", "false,SUCCEEDED,200"})
    void returnsAcceptedDiaryOrReusesIdempotentResult(
            boolean newlyCreated, GenerationStatus generationStatus, int statusCode
    ) throws Exception {
        Instant now = Instant.now();
        UUID generationId = UUID.randomUUID();
        boolean succeeded = generationStatus == GenerationStatus.SUCCEEDED;
        when(creationService.create(any())).thenReturn(new CreateDiaryResult(
                DIARY_ID, DATE, "부하 테스트 일기", now,
                new DiaryGenerationResult(generationId, generationStatus,
                        succeeded ? "부하 테스트 일기" : null, succeeded ? "image-key" : null, succeeded ? now : null),
                new GenerationUsage(DATE, 1, 3), newlyCreated));
        if (succeeded) {
            when(imageUrlProvider.createAccessUrl("image-key"))
                    .thenReturn(new ImageAccessUrl(URI.create("https://images.invalid/diary.webp"), now.plusSeconds(900)));
        }
        var response = mvc.perform(post(PATH)
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.id").value(DIARY_ID.toString()))
                .andExpect(jsonPath("$.generation.id").value(generationId.toString()))
                .andExpect(jsonPath("$.generation.status").value(generationStatus.name()))
                .andExpect(jsonPath("$.usage.remainingCount").value(2));
        if (newlyCreated) {
            response.andExpect(header().string("Location", "/api/v1/diaries/" + DIARY_ID));
        } else {
            response.andExpect(header().doesNotExist("Location"));
        }
        if (!succeeded) {
            verifyNoInteractions(imageUrlProvider);
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
