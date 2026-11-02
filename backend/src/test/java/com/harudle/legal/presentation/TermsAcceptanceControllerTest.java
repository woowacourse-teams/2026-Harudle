package com.harudle.legal.presentation;

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
import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.legal.application.InvalidTermsAcceptanceException;
import com.harudle.legal.application.LegalDocumentChangedException;
import com.harudle.legal.application.TermsAcceptanceCommand;
import com.harudle.legal.application.TermsAcceptanceService;
import com.harudle.legal.application.TermsAcceptanceStatus;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

@WebMvcTest(TermsAcceptanceController.class)
@Import({AuthenticatedUserIdResolver.class,
        ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class, TraceIdConfiguration.class,
        CsrfConfiguration.class, SecurityConfig.class})
class TermsAcceptanceControllerTest {
    private static final UUID ACTOR = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final String PATH = "/api/v1/me/agreements";
    private static final String TERMS_VERSION = "2026-10-22";
    private static final String TERMS_HASH = "a".repeat(64);
    private static final String PRIVACY_VERSION = "2026-08-24";
    private static final String PRIVACY_HASH = "b".repeat(64);
    private static final Instant ACCEPTED_AT = Instant.parse("2026-10-25T06:00:00Z");
    private static final String BODY = """
            {"termsVersion":"%s","termsContentHash":"%s","privacyNoticeVersion":"%s","privacyNoticeContentHash":"%s","accepted":true,"ageConfirmed":true}
            """.formatted(TERMS_VERSION, TERMS_HASH, PRIVACY_VERSION, PRIVACY_HASH);

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TermsAcceptanceService service;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private UserRepository users;
    @MockitoBean private OAuthLoginSuccessHandler successHandler;
    @MockitoBean private OAuthLoginFailureHandler failureHandler;
    @MockitoBean private ClientRegistrationRepository registrations;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
        when(decoder.decode("valid-token")).thenReturn(token("valid-token", ACTOR.toString()));
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("수락자는 Bearer subject로 결정하고 기존 Bearer API 정책대로 CSRF 없이 수락 결과와 no-store를 반환한다")
    void acceptsForTheAuthenticatedMemberWithoutAddingACsrfRequirement() {
        when(service.accept(eq(ACTOR), any())).thenReturn(status(false, ACCEPTED_AT));

        MockMvcResponse response = authorized().body(BODY).put(PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.contentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.jsonPath().getMap("$")).containsOnlyKeys(
                "enforcementEnabled", "requiresAcceptance", "terms", "privacyNotice", "acceptedAt", "minimumAge");
        assertThat(response.jsonPath().getBoolean("enforcementEnabled")).isTrue();
        assertThat(response.jsonPath().getInt("minimumAge")).isEqualTo(14);
        assertThat(response.jsonPath().getBoolean("requiresAcceptance")).isFalse();
        assertThat(response.jsonPath().getString("acceptedAt")).isEqualTo(ACCEPTED_AT.toString());
        assertThat(response.jsonPath().getString("terms.version")).isEqualTo(TERMS_VERSION);
        assertThat(response.jsonPath().getString("terms.contentHash")).isEqualTo(TERMS_HASH);
        assertThat(response.jsonPath().getString("privacyNotice.version")).isEqualTo(PRIVACY_VERSION);
        assertThat(response.jsonPath().getString("privacyNotice.contentHash")).isEqualTo(PRIVACY_HASH);
        verify(service).accept(ACTOR, new TermsAcceptanceCommand(
                TERMS_VERSION, TERMS_HASH, PRIVACY_VERSION, PRIVACY_HASH, true, true));
    }

    @Test
    @DisplayName("본인의 현재 동의 필요 상태와 문서 참조를 캐시하지 않는 응답으로 조회한다")
    void readsCurrentAcceptanceStatusForTheAuthenticatedMember() {
        when(service.status(ACTOR)).thenReturn(status(true, null));

        MockMvcResponse response = authorized().get(PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.jsonPath().getBoolean("requiresAcceptance")).isTrue();
        assertThat(response.jsonPath().getInt("minimumAge")).isEqualTo(14);
        assertThat(response.jsonPath().getString("acceptedAt")).isNull();
        assertThat(response.jsonPath().getString("terms.version")).isEqualTo(TERMS_VERSION);
        assertThat(response.jsonPath().getString("privacyNotice.contentHash")).isEqualTo(PRIVACY_HASH);
        verify(service).status(ACTOR);
    }

    @Test
    @DisplayName("로그인하지 않았거나 Bearer 검증에 실패하면 동의 조회·수락을 인증 오류로 거절한다")
    void rejectsAnonymousAndInvalidBearerRequests() {
        MockMvcResponse read = RestAssuredMockMvc.given().get(PATH);
        MockMvcResponse write = RestAssuredMockMvc.given().contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(BODY).put(PATH);
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("invalid"));
        MockMvcResponse invalid = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                .contentType(MediaType.APPLICATION_JSON_VALUE).body(BODY).put(PATH);

        assertThat(read.statusCode()).isEqualTo(401);
        assertThat(write.statusCode()).isEqualTo(401);
        assertThat(write.jsonPath().getString("code")).isEqualTo("UNAUTHORIZED");
        assertThat(invalid.statusCode()).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "08d69a34-6d70-4d42-a158-671bc67733c"})
    @DisplayName("서명 검증을 통과했어도 정규 UUID가 아닌 subject는 서비스에 넘기지 않는다")
    void rejectsMalformedAuthenticatedSubjects(String subject) {
        when(decoder.decode("valid-token")).thenReturn(token("valid-token", subject));

        MockMvcResponse read = authorized().get(PATH);
        MockMvcResponse write = authorized().body(BODY).put(PATH);

        assertThat(read.statusCode()).isEqualTo(401);
        assertThat(read.jsonPath().getString("code")).isEqualTo("UNAUTHORIZED");
        assertThat(write.statusCode()).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("unacceptedBodies")
    @DisplayName("수락이 false·null이거나 빠진 요청은 DTO 검증에서 400으로 거절하고 서비스를 호출하지 않는다")
    void rejectsMissingOrFalseAcceptanceBeforeCallingTheService(String body) {
        assertValidationFailure(body);
    }

    @ParameterizedTest
    @MethodSource("unconfirmedAgeBodies")
    @DisplayName("만 14세 이상 자가 확인이 false·null이거나 빠졌으면 서비스 호출 전에 거절한다")
    void rejectsMissingOrFalseMinimumAgeDeclaration(String body) {
        assertValidationFailure(body);
    }

    @ParameterizedTest
    @MethodSource("invalidDocumentReferences")
    @DisplayName("버전·본문 식별값이 없거나 형식이 잘못됐으면 저장 서비스에 전달하지 않는다")
    void rejectsInvalidDocumentReferencesBeforeCallingTheService(String body) {
        assertValidationFailure(body);
    }

    @Test
    @DisplayName("형식이 유효한 이전 버전 제출은 문서 변경 409와 최신 문서 재확인 안내로 매핑한다")
    void mapsAChangedDocumentToAConflictResponse() {
        when(service.accept(eq(ACTOR), any())).thenThrow(new LegalDocumentChangedException());
        String previous = BODY.replace(TERMS_VERSION, "2026-10-21");

        MockMvcResponse response = authorized().body(previous).put(PATH);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.contentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.jsonPath().getString("code")).isEqualTo("LEGAL_DOCUMENT_CHANGED");
        assertThat(response.jsonPath().getString("instance")).isEqualTo(PATH);
        assertThat(response.jsonPath().getString("traceId")).isNotBlank();
        verify(service).accept(ACTOR, new TermsAcceptanceCommand(
                "2026-10-21", TERMS_HASH, PRIVACY_VERSION, PRIVACY_HASH, true, true));
    }

    @Test
    @DisplayName("서비스에서 활성 회원을 확인할 수 없으면 현재 상태 대신 401을 반환한다")
    void mapsInactiveMembershipToUnauthorized() {
        when(service.status(ACTOR)).thenThrow(new AuthenticationRequiredException());

        MockMvcResponse response = authorized().get(PATH);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.jsonPath().getString("code")).isEqualTo("UNAUTHORIZED");
        assertThat(response.header(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        verify(service).status(ACTOR);
    }

    @Test
    @DisplayName("서비스의 수락 거절은 고정된 400 오류로 매핑하고 요청 문서 해시를 오류 응답에 노출하지 않는다")
    void mapsInvalidAcceptanceWithoutEchoingSubmittedReferences() {
        when(service.accept(eq(ACTOR), any())).thenThrow(new InvalidTermsAcceptanceException());

        MockMvcResponse response = authorized().body(BODY).put(PATH);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_TERMS_ACCEPTANCE");
        assertThat(response.asString()).doesNotContain(TERMS_HASH, PRIVACY_HASH);
    }

    private void assertValidationFailure(String body) {
        MockMvcResponse response = authorized().body(body).put(PATH);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(service);
    }

    private MockMvcRequestSpecification authorized() {
        return RestAssuredMockMvc.given().header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON_VALUE);
    }

    private static TermsAcceptanceStatus status(boolean requiresAcceptance, Instant acceptedAt) {
        return new TermsAcceptanceStatus(true, requiresAcceptance,
                new TermsAcceptanceStatus.DocumentReference(TERMS_VERSION, TERMS_HASH),
                new TermsAcceptanceStatus.DocumentReference(PRIVACY_VERSION, PRIVACY_HASH), acceptedAt, 14);
    }

    private static Jwt token(String token, String subject) {
        return Jwt.withTokenValue(token).header("alg", "HS256").subject(subject)
                .issuedAt(ACCEPTED_AT).expiresAt(ACCEPTED_AT.plusSeconds(1800)).build();
    }

    private static Stream<String> unacceptedBodies() {
        return Stream.of(BODY.replace("\"accepted\":true", "\"accepted\":false"),
                BODY.replace("\"accepted\":true", "\"accepted\":null"),
                BODY.replace(",\"accepted\":true", ""));
    }

    private static Stream<String> unconfirmedAgeBodies() {
        return Stream.of(BODY.replace("\"ageConfirmed\":true", "\"ageConfirmed\":false"),
                BODY.replace("\"ageConfirmed\":true", "\"ageConfirmed\":null"),
                BODY.replace(",\"ageConfirmed\":true", ""));
    }

    private static Stream<String> invalidDocumentReferences() {
        return Stream.of(
                BODY.replace("\"termsVersion\":\"" + TERMS_VERSION + "\",", ""),
                BODY.replace("\"termsContentHash\":\"" + TERMS_HASH + "\",", ""),
                BODY.replace("\"privacyNoticeVersion\":\"" + PRIVACY_VERSION + "\",", ""),
                BODY.replace("\"privacyNoticeContentHash\":\"" + PRIVACY_HASH + "\",", ""),
                BODY.replace(TERMS_VERSION, "../terms"),
                BODY.replace(TERMS_HASH, "a".repeat(63)),
                BODY.replace(PRIVACY_VERSION, "p".repeat(65)),
                BODY.replace(PRIVACY_HASH, "B".repeat(64)));
    }
}
