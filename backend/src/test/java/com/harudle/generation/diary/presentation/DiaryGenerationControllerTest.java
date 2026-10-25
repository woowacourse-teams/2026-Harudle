package com.harudle.generation.diary.presentation;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.service.DiaryGenerationStatusQueryService;
import com.harudle.generation.diary.service.dto.DiaryGenerationStatusResult;
import com.harudle.generation.diary.service.exception.DiaryGenerationNotFoundException;
import com.harudle.guest.application.exception.GuestSessionExpiredException;
import com.harudle.guest.configuration.GuestSessionCookieProperties;
import com.harudle.guest.infrastructure.cookie.GuestSessionCookieReader;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(controllers = {DiaryGenerationController.class, GuestDiaryGenerationController.class}, properties = {
        "harudle.guest.cookie.name=guest_session", "harudle.guest.cookie.path=/",
        "harudle.guest.cookie.secure=false", "harudle.guest.cookie.same-site=Lax"
})
@Import({AuthenticatedUserIdResolver.class, GuestSessionCookieReader.class,
        DiaryGenerationControllerTest.CookieConfiguration.class,
        ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class, TraceIdConfiguration.class,
        CsrfConfiguration.class, SecurityConfig.class})
class DiaryGenerationControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID GENERATION_ID = UUID.randomUUID();
    private static final UUID DIARY_ID = UUID.randomUUID();
    private static final String RAW_TOKEN = "guest-session-token";

    @Autowired private MockMvc mvc;
    @MockitoBean private DiaryGenerationStatusQueryService queryService;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private OAuthLoginSuccessHandler successHandler;
    @MockitoBean private OAuthLoginFailureHandler failureHandler;
    @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

    @ParameterizedTest
    @CsvSource({"false,PENDING", "false,PROCESSING", "false,SUCCEEDED", "false,FAILED",
            "true,PENDING", "true,PROCESSING", "true,SUCCEEDED", "true,FAILED"})
    @DisplayName("회원과 게스트의 네 가지 생성 상태를 200으로 반환하고 캐시하지 않는다")
    void getGenerationStatus(boolean guest, GenerationStatus generationStatus) throws Exception {
        UUID diaryId = generationStatus == GenerationStatus.SUCCEEDED ? DIARY_ID : null;
        GenerationErrorCode errorCode = generationStatus == GenerationStatus.FAILED
                ? GenerationErrorCode.AI_PROVIDER_TIMEOUT : null;
        var result = new DiaryGenerationStatusResult(GENERATION_ID, generationStatus, diaryId, errorCode);
        if (guest) {
            when(queryService.getGuestStatus(RAW_TOKEN, GENERATION_ID)).thenReturn(result);
        } else {
            when(queryService.getStatus(USER_ID, GENERATION_ID)).thenReturn(result);
        }

        mvc.perform(request(guest, GENERATION_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(GENERATION_ID.toString()))
                .andExpect(jsonPath("$.status").value(generationStatus.name()))
                .andExpect(jsonPath("$.diaryId").value(diaryId == null ? nullValue() : org.hamcrest.Matchers.is(diaryId.toString())))
                .andExpect(jsonPath("$.errorCode").value(errorCode == null ? nullValue() : org.hamcrest.Matchers.is(errorCode.name())))
                .andExpect(jsonPath("$.action").value(generationStatus == GenerationStatus.FAILED
                        ? org.hamcrest.Matchers.is(guest ? "체험 생성 시간이 초과됐어요. 로그인 후 다시 생성해 주세요."
                                : "생성 시간이 초과됐어요. 잠시 후 다시 생성해 주세요.")
                        : nullValue()))
                .andExpect(jsonPath("$.imageUrl").doesNotExist())
                .andExpect(jsonPath("$.sourceText").doesNotExist());
        if (guest) {
            verify(queryService).getGuestStatus(RAW_TOKEN, GENERATION_ID);
        } else {
            verify(queryService).getStatus(USER_ID, GENERATION_ID);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("회원과 게스트의 대기 만료 결과는 200과 FAILED로 반환하고 캐시하지 않는다")
    void getPendingExpiredGenerationStatus(boolean guest) throws Exception {
        var result = new DiaryGenerationStatusResult(
                GENERATION_ID, GenerationStatus.FAILED, null, GenerationErrorCode.GENERATION_PENDING_TIMEOUT
        );
        if (guest) {
            when(queryService.getGuestStatus(RAW_TOKEN, GENERATION_ID)).thenReturn(result);
        } else {
            when(queryService.getStatus(USER_ID, GENERATION_ID)).thenReturn(result);
        }

        mvc.perform(request(guest, GENERATION_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(GENERATION_ID.toString()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.diaryId").value(nullValue()))
                .andExpect(jsonPath("$.errorCode").value("GENERATION_PENDING_TIMEOUT"))
                .andExpect(jsonPath("$.action").value(guest
                        ? "대기 시간이 지나 체험 생성을 시작하지 못했어요. 로그인 후 다시 생성해 주세요."
                        : "대기 시간이 지나 생성을 시작하지 못했어요. 잠시 후 다시 생성해 주세요."));
    }

    @Test
    @DisplayName("회원의 상태 조회는 로그인하지 않으면 거절한다")
    void rejectUnauthenticatedMember() throws Exception {
        mvc.perform(get("/api/v1/diary-generations/{id}", GENERATION_ID))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("게스트 상태 조회는 로그인 대신 게스트 쿠키를 요구한다")
    void rejectMissingGuestCookieEvenWhenLoggedIn() throws Exception {
        mvc.perform(get("/api/v1/guest/diary-generations/{id}", GENERATION_ID).with(jwt()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GUEST_SESSION_REQUIRED"));
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("만료된 게스트 쿠키는 401로 거절한다")
    void rejectExpiredGuestSession() throws Exception {
        when(queryService.getGuestStatus(RAW_TOKEN, GENERATION_ID)).thenThrow(new GuestSessionExpiredException());
        mvc.perform(request(true, GENERATION_ID.toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GUEST_SESSION_EXPIRED"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("존재하지 않거나 다른 사람의 작업은 같은 404 응답을 반환한다")
    void rejectMissingOrUnownedGeneration(boolean guest) throws Exception {
        if (guest) {
            when(queryService.getGuestStatus(RAW_TOKEN, GENERATION_ID)).thenThrow(new DiaryGenerationNotFoundException());
        } else {
            when(queryService.getStatus(USER_ID, GENERATION_ID)).thenThrow(new DiaryGenerationNotFoundException());
        }
        mvc.perform(request(guest, GENERATION_ID.toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GENERATION_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("생성 ID가 UUID가 아니면 조회하지 않는다")
    void rejectInvalidGenerationId(boolean guest) throws Exception {
        mvc.perform(request(guest, "invalid-id"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(queryService);
    }

    private MockHttpServletRequestBuilder request(boolean guest, String id) {
        if (guest) {
            return get("/api/v1/guest/diary-generations/{id}", id).cookie(new Cookie("guest_session", RAW_TOKEN));
        }
        return get("/api/v1/diary-generations/{id}", id).with(jwt().jwt(token -> token.subject(USER_ID.toString())));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GuestSessionCookieProperties.class)
    static class CookieConfiguration {
    }
}
