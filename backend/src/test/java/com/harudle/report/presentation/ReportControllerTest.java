package com.harudle.report.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.ReportSecurityConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.report.domain.InvalidReportException;
import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportSubmission;
import com.harudle.report.domain.ReportTargetType;
import com.harudle.report.service.ReportReceipt;
import com.harudle.report.service.ReportService;
import com.harudle.report.service.exception.ReportTargetNotFoundException;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReportController.class)
@Import({AuthenticatedUserIdResolver.class, ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class,
        TraceIdConfiguration.class, CsrfConfiguration.class, SecurityConfig.class, ReportSecurityConfiguration.class})
class ReportControllerTest {

    private static final UUID ACTOR = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID TARGET = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID REPORT = UUID.fromString("4a08c9a2-7531-4927-a898-6fe49cc303ea");
    private static final String PATH = "/api/v1/reports";
    private static final String CSRF = "same-cookie-and-header";
    private static final String BODY = """
            {"targetType":"FEED","targetId":"%s","reason":"SPAM","description":"신고 상세"}
            """.formatted(TARGET);

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ReportService service;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository users;
    @MockitoBean private OAuthLoginSuccessHandler successHandler;
    @MockitoBean private OAuthLoginFailureHandler failureHandler;
    @MockitoBean private ClientRegistrationRepository registrations;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
        Instant issuedAt = Instant.parse("2026-10-22T00:00:00Z");
        when(jwtDecoder.decode("valid-token")).thenReturn(Jwt.withTokenValue("valid-token")
                .header("alg", "HS256").subject(ACTOR.toString()).issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(1800)).build());
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("실제 Bearer와 CSRF를 검증하고 신고자 정보 없이 새 접수를 반환한다")
    void createsReceiptUsingAuthenticatedActor() {
        when(service.submit(eq(ACTOR), any())).thenReturn(new ReportReceipt(REPORT, false));

        MockMvcResponse response = authorized().body(BODY).post(PATH);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.header(HttpHeaders.LOCATION)).isEqualTo(PATH + "/" + REPORT);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.jsonPath().getMap("$")).containsOnlyKeys("id", "status", "duplicate");
        assertThat(response.jsonPath().getString("id")).isEqualTo(REPORT.toString());
        assertThat(response.jsonPath().getString("status")).isEqualTo("RECEIVED");
        assertThat(response.jsonPath().getBoolean("duplicate")).isFalse();
        verify(service).submit(ACTOR, new ReportSubmission(ReportTargetType.FEED, TARGET, ReportReason.SPAM, "신고 상세"));
    }

    @Test
    @DisplayName("중복 접수는 200과 기존 ID를 반환하고 새 접수 URI를 만들지 않는다")
    void returnsPreviousReceiptWith200() {
        when(service.submit(eq(ACTOR), any())).thenReturn(new ReportReceipt(REPORT, true));
        MockMvcResponse response = authorized().body(BODY).post(PATH);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getBoolean("duplicate")).isTrue();
        assertThat(response.jsonPath().getString("id")).isEqualTo(REPORT.toString());
        assertThat(response.header(HttpHeaders.LOCATION)).isNull();
    }

    @Test
    @DisplayName("비로그인과 잘못된 Bearer는 CSRF 검사보다 먼저 401을 반환한다")
    void rejectsAnonymousAndInvalidTokenBeforeCsrf() {
        MockMvcResponse anonymous = RestAssuredMockMvc.given().contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(BODY).post(PATH);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("invalid"));
        MockMvcResponse invalid = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token").contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(BODY).post(PATH);
        assertThat(invalid.statusCode()).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("인증된 요청도 CSRF 쿠키와 헤더가 없거나 다르면 접수하지 않는다")
    void requiresMatchingCsrfCookieAndHeader() {
        MockMvcResponse missing = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token").contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(BODY).post(PATH);
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(missing.jsonPath().getString("code")).isEqualTo("INVALID_CSRF_TOKEN");
        MockMvcResponse mismatch = authorized("other").body(BODY).post(PATH);
        assertThat(mismatch.statusCode()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("대상과 사유 및 문자 경계의 잘못된 입력은 서비스 호출 전에 거절한다")
    void rejectsInvalidInput(String body) {
        MockMvcResponse response = authorized().body(body).post(PATH);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("500개 이모지를 허용하며 상세가 없으면 빈 문자열로 전달한다")
    void acceptsUnicodeBoundaryAndOptionalDescription() {
        when(service.submit(eq(ACTOR), any())).thenReturn(new ReportReceipt(REPORT, false));
        String content = "😀".repeat(500);
        assertThat(authorized().body(BODY.replace("신고 상세", content)).post(PATH).statusCode()).isEqualTo(201);
        String noDescription = "{\"targetType\":\"USER\",\"targetId\":\"" + TARGET + "\",\"reason\":\"OTHER\"}";
        assertThat(authorized().body(noDescription).post(PATH).statusCode()).isEqualTo(201);
        verify(service).submit(ACTOR, new ReportSubmission(ReportTargetType.USER, TARGET, ReportReason.OTHER, ""));
    }

    @Test
    @DisplayName("자기신고와 숨김·삭제 대상 오류를 고정된 Problem Details로 응답한다")
    void mapsBusinessErrorsWithoutLeakingReportDetails() {
        when(service.submit(eq(ACTOR), any())).thenThrow(new InvalidReportException());
        MockMvcResponse invalid = authorized().body(BODY).post(PATH);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.jsonPath().getString("code")).isEqualTo("INVALID_REPORT");
        assertThat(invalid.asString()).doesNotContain("신고 상세");
        when(service.submit(eq(ACTOR), any())).thenThrow(new ReportTargetNotFoundException());
        MockMvcResponse missing = authorized().body(BODY).post(PATH);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.jsonPath().getString("code")).isEqualTo("REPORT_TARGET_NOT_FOUND");
    }

    @Test
    @DisplayName("다른 사람의 신고를 조회하는 API는 제공하지 않는다")
    void doesNotExposeReceiptReadEndpoint() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token").get(PATH + "/" + REPORT);
        assertThat(response.statusCode()).isEqualTo(404);
        verifyNoInteractions(service);
    }

    private MockMvcRequestSpecification authorized() {
        return authorized(CSRF);
    }

    private MockMvcRequestSpecification authorized(String csrfHeader) {
        return RestAssuredMockMvc.given().header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .header("X-XSRF-TOKEN", csrfHeader).postProcessors(request -> {
                    request.setCookies(new Cookie("XSRF-TOKEN", CSRF));
                    return request;
                }).contentType(MediaType.APPLICATION_JSON_VALUE);
    }

    private static Stream<String> invalidBodies() {
        return Stream.of("{}", BODY.replace("FEED", "UNKNOWN"), BODY.replace(TARGET.toString(), "invalid-id"),
                BODY.replace("SPAM", "UNKNOWN"), BODY.replace("신고 상세", "😀".repeat(501)),
                BODY.replace("신고 상세", "\\u0000"), BODY.replace("신고 상세", "\\ud800"));
    }
}
