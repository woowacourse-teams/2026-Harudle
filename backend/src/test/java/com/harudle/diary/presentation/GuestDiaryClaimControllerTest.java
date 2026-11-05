package com.harudle.diary.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.InvalidCurrentUserException;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.config.TimeConfiguration;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.diary.service.GuestDiaryClaimService;
import com.harudle.diary.service.dto.GuestDiaryClaimResult;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.guest.application.exception.GuestDiaryAlreadyClaimedException;
import com.harudle.guest.application.exception.GuestDiaryClaimForbiddenException;
import com.harudle.guest.application.exception.GuestDiaryClaimLimitExceededException;
import com.harudle.guest.application.exception.GuestDiaryClaimNotFoundException;
import com.harudle.guest.configuration.GuestSessionCookieProperties;
import com.harudle.guest.infrastructure.cookie.GuestSessionCookieReader;
import com.harudle.guest.infrastructure.cookie.GuestSessionCookieWriter;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GuestDiaryClaimController.class)
@Import({
        AuthenticatedUserIdResolver.class,
        GuestSessionCookieReader.class,
        GuestSessionCookieWriter.class,
        GuestDiaryClaimControllerTest.CookiePropertiesConfiguration.class,
        ApiExceptionLoggerTestConfiguration.class,
        ProblemDetailFactory.class,
        TraceIdConfiguration.class,
        CsrfConfiguration.class,
        SecurityConfig.class,
        TimeConfiguration.class
})
class GuestDiaryClaimControllerTest {

    private static final String PATH = "/api/v1/guest/diary-claim";
    private static final String CSRF = "matching-cookie-header";
    private static final String RAW_TOKEN = "guest-session-token";
    private static final UUID MEMBER_ID = UUID.fromString("0b8d6f2e-3c4a-4e5f-9a1b-2c3d4e5f6a7b");
    private static final UUID DIARY_ID = UUID.fromString("593363cb-1dc3-46bc-a858-5926f7601ca9");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GuestDiaryClaimService claimService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
        when(jwtDecoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid").header("alg", "HS256")
                .subject(MEMBER_ID.toString()).build());
        when(userRepository.existsById(MEMBER_ID)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("체험 일기를 가져오면 결과를 반환하고 게스트 세션 Cookie를 만료한다")
    void claimGuestDiary() {
        when(claimService.claim(MEMBER_ID, RAW_TOKEN)).thenReturn(new GuestDiaryClaimResult(
                DIARY_ID,
                LocalDate.of(2026, 11, 5),
                GenerationStatus.PROCESSING,
                Instant.parse("2026-11-05T01:02:03Z")
        ));

        MockMvcResponse response = authorized(CSRF, true).post(PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.jsonPath().getMap("$"))
                .containsOnlyKeys("diaryId", "diaryDate", "generationStatus", "claimedAt");
        assertThat(response.jsonPath().getString("diaryId")).isEqualTo(DIARY_ID.toString());
        assertThat(response.jsonPath().getString("diaryDate")).isEqualTo("2026-11-05");
        assertThat(response.jsonPath().getString("generationStatus")).isEqualTo("PROCESSING");
        assertThat(response.jsonPath().getString("claimedAt")).isEqualTo("2026-11-05T10:02:03+09:00");
        assertThat(response.headers().getValues(HttpHeaders.SET_COOKIE))
                .anySatisfy(cookie -> assertThat(cookie)
                        .startsWith("guest_session=;")
                        .contains("Max-Age=0", "Path=/api/v1/guest", "HttpOnly"));
    }

    @Test
    @DisplayName("게스트 세션 Cookie가 없으면 빈 토큰으로 조회해 404를 그대로 응답한다")
    void returnNotFoundWithoutGuestCookie() {
        when(claimService.claim(eq(MEMBER_ID), isNull())).thenThrow(new GuestDiaryClaimNotFoundException());

        MockMvcResponse response = authorized(CSRF, false).post(PATH);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.jsonPath().getString("code")).isEqualTo("GUEST_DIARY_CLAIM_NOT_FOUND");
        assertThat(response.headers().getValues(HttpHeaders.SET_COOKIE))
                .noneSatisfy(cookie -> assertThat(cookie).startsWith("guest_session="));
    }

    @ParameterizedTest
    @MethodSource("claimFailures")
    @DisplayName("가져오기 실패는 정해진 오류 코드로 응답하고 게스트 세션 Cookie를 유지한다")
    void mapClaimFailures(RuntimeException exception, ErrorType expected) {
        when(claimService.claim(MEMBER_ID, RAW_TOKEN)).thenThrow(exception);

        MockMvcResponse response = authorized(CSRF, true).post(PATH);

        assertThat(response.statusCode()).isEqualTo(expected.status().value());
        assertThat(response.jsonPath().getString("code")).isEqualTo(expected.name());
        assertThat(response.headers().getValues(HttpHeaders.SET_COOKIE))
                .noneSatisfy(cookie -> assertThat(cookie).startsWith("guest_session="));
    }

    private static Stream<Arguments> claimFailures() {
        return Stream.of(
                Arguments.of(new GuestDiaryClaimNotFoundException(), ErrorType.GUEST_DIARY_CLAIM_NOT_FOUND),
                Arguments.of(new GuestDiaryAlreadyClaimedException(), ErrorType.GUEST_DIARY_ALREADY_CLAIMED),
                Arguments.of(
                        new GuestDiaryClaimLimitExceededException(),
                        ErrorType.GUEST_DIARY_CLAIM_LIMIT_EXCEEDED
                ),
                Arguments.of(new GuestDiaryClaimForbiddenException(), ErrorType.FORBIDDEN),
                Arguments.of(new InvalidCurrentUserException(), ErrorType.INVALID_CURRENT_USER)
        );
    }

    @Test
    @DisplayName("Bearer 토큰이 없으면 게스트 세션 Cookie가 있어도 401로 거절한다")
    void rejectWithoutBearerToken() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .header("X-XSRF-TOKEN", CSRF)
                .postProcessors(request -> {
                    request.setCookies(new Cookie("XSRF-TOKEN", CSRF), new Cookie("guest_session", RAW_TOKEN));
                    return request;
                })
                .post(PATH);

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(claimService);
    }

    @Test
    @DisplayName("CSRF 토큰이 없거나 다르면 가져오기를 실행하지 않는다")
    void rejectMissingOrMismatchedCsrf() {
        MockMvcResponse missing = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid")
                .postProcessors(request -> {
                    request.setCookies(new Cookie("guest_session", RAW_TOKEN));
                    return request;
                })
                .post(PATH);
        MockMvcResponse mismatched = authorized("different", true).post(PATH);

        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(missing.jsonPath().getString("code")).isEqualTo("INVALID_CSRF_TOKEN");
        assertThat(mismatched.statusCode()).isEqualTo(403);
        assertThat(mismatched.jsonPath().getString("code")).isEqualTo("INVALID_CSRF_TOKEN");
        verifyNoInteractions(claimService);
    }

    @Test
    @DisplayName("요청 본문 없이 인증 주체의 회원 ID와 Cookie 토큰만 서비스에 전달한다")
    void passAuthenticatedMemberAndCookieToken() {
        when(claimService.claim(any(), any())).thenThrow(new GuestDiaryClaimNotFoundException());

        authorized(CSRF, true).post(PATH);

        verify(claimService).claim(MEMBER_ID, RAW_TOKEN);
    }

    private MockMvcRequestSpecification authorized(String csrfHeader, boolean withGuestCookie) {
        return RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid")
                .header("X-XSRF-TOKEN", csrfHeader)
                .postProcessors(request -> {
                    Cookie csrfCookie = new Cookie("XSRF-TOKEN", CSRF);
                    if (withGuestCookie) {
                        request.setCookies(csrfCookie, new Cookie("guest_session", RAW_TOKEN));
                    } else {
                        request.setCookies(csrfCookie);
                    }
                    return request;
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CookiePropertiesConfiguration {

        @Bean
        GuestSessionCookieProperties guestSessionCookieProperties() {
            return new GuestSessionCookieProperties("guest_session", "/api/v1/guest", true, "Lax");
        }
    }
}
