package com.harudle.diary.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.common.config.TimeConfiguration;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.ApiCorsConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.diary.service.GuestDiaryCreationService;
import com.harudle.diary.service.GuestDiaryQueryService;
import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.guest.application.exception.GuestSessionExpiredException;
import com.harudle.guest.application.exception.GuestTrialAlreadyUsedException;
import com.harudle.guest.infrastructure.cookie.GuestSessionCookieReader;
import io.restassured.http.ContentType;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(GuestDiaryController.class)
@Import({
        ApiCorsConfiguration.class,
        DiaryResponseAssembler.class,
        ApiExceptionLoggerTestConfiguration.class,
        ProblemDetailFactory.class,
        TraceIdConfiguration.class,
        CsrfConfiguration.class,
        SecurityConfig.class,
        TimeConfiguration.class
})
@TestPropertySource(properties = "app.auth.frontend-origins=http://localhost:5173")
class GuestDiaryControllerTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:5173";
    private static final UUID DIARY_ID = UUID.fromString("593363cb-1dc3-46bc-a858-5926f7601ca9");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final String RAW_TOKEN = "guest-session-token";
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 20);
    private static final Instant CREATED_AT = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-20T00:01:00Z");
    private static final Instant IMAGE_EXPIRES_AT = Instant.parse("2026-08-20T00:10:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GuestDiaryCreationService guestDiaryCreationService;

    @MockitoBean
    private GuestDiaryQueryService guestDiaryQueryService;

    @MockitoBean
    private GuestSessionCookieReader guestSessionCookieReader;

    @MockitoBean
    private ImageUrlProvider imageUrlProvider;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private OAuthLoginSuccessHandler oAuthLoginSuccessHandler;

    @MockitoBean
    private OAuthLoginFailureHandler oAuthLoginFailureHandler;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("Access Token 없이 게스트 일기를 한 번 생성하고 201을 반환한다")
    void createGuestDiary() {
        configureGuestSessionCookie();
        configureImageUrl();
        when(guestDiaryCreationService.create(
                eq(RAW_TOKEN),
                any(CreateGuestDiaryCommand.class)
        )).thenReturn(createGuestResult(true));

        MockMvcResponse response = guestPostRequest()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body(validRequestBody())
                .post("/api/v1/guest/diaries");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.header("Location"))
                .isEqualTo("/api/v1/guest/diaries/" + DIARY_ID);
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
        assertThat(response.jsonPath().getString("id")).isEqualTo(DIARY_ID.toString());
        assertThat(response.jsonPath().getString("generation.imageUrl"))
                .isEqualTo("https://images.harudle.example/comic.png");
        assertThat(response.asString()).doesNotContain("usage", "imageObjectKey");
    }

    @Test
    @DisplayName("같은 게스트 멱등 요청은 기존 결과를 200으로 반환한다")
    void replayGuestDiaryCreation() {
        configureGuestSessionCookie();
        configureImageUrl();
        when(guestDiaryCreationService.create(
                eq(RAW_TOKEN),
                any(CreateGuestDiaryCommand.class)
        )).thenReturn(createGuestResult(false));

        MockMvcResponse response = guestPostRequest()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body(validRequestBody())
                .post("/api/v1/guest/diaries");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Location")).isNull();
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    @DisplayName("게스트 세션에 연결된 일기 결과를 조회한다")
    void getGuestDiaryDetail() {
        configureGuestSessionCookie();
        configureImageUrl();
        when(guestDiaryQueryService.getDetail(RAW_TOKEN, DIARY_ID))
                .thenReturn(createDetailResult());

        MockMvcResponse response = RestAssuredMockMvc.given()
                .cookie("guest_session", RAW_TOKEN)
                .get("/api/v1/guest/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
        assertThat(response.jsonPath().getString("generation.title"))
                .isEqualTo("친구와 보낸 하루");
    }

    @Test
    @DisplayName("게스트 세션 Cookie가 없으면 401 Problem Details를 반환한다")
    void rejectRequestWithoutGuestSession() {
        when(guestSessionCookieReader.read(any(HttpServletRequest.class)))
                .thenReturn(Optional.empty());

        MockMvcResponse response = guestPostRequest()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body(validRequestBody())
                .post("/api/v1/guest/diaries");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.jsonPath().getString("code")).isEqualTo("GUEST_SESSION_REQUIRED");
        verifyNoInteractions(guestDiaryCreationService);
    }

    @Test
    @DisplayName("이미 사용한 게스트 세션의 새로운 생성 요청은 409를 반환한다")
    void rejectSecondGuestDiaryCreation() {
        configureGuestSessionCookie();
        when(guestDiaryCreationService.create(
                eq(RAW_TOKEN),
                any(CreateGuestDiaryCommand.class)
        )).thenThrow(new GuestTrialAlreadyUsedException());

        MockMvcResponse response = guestPostRequest()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body(validRequestBody())
                .post("/api/v1/guest/diaries");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.jsonPath().getString("code"))
                .isEqualTo("GUEST_TRIAL_ALREADY_USED");
    }

    @Test
    @DisplayName("만료된 게스트 세션은 401 Problem Details를 반환한다")
    void rejectExpiredGuestSession() {
        configureGuestSessionCookie();
        when(guestDiaryCreationService.create(
                eq(RAW_TOKEN),
                any(CreateGuestDiaryCommand.class)
        )).thenThrow(new GuestSessionExpiredException());

        MockMvcResponse response = guestPostRequest()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body(validRequestBody())
                .post("/api/v1/guest/diaries");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.jsonPath().getString("code"))
                .isEqualTo("GUEST_SESSION_EXPIRED");
    }

    @Test
    @DisplayName("CSRF Header가 없으면 게스트 일기를 생성하지 않는다")
    void rejectGuestDiaryCreationWithoutCsrf() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .cookie("guest_session", RAW_TOKEN)
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body(validRequestBody())
                .post("/api/v1/guest/diaries");

        assertThat(response.statusCode()).isEqualTo(403);
        verifyNoInteractions(guestDiaryCreationService);
    }

    @Test
    @DisplayName("게스트 일기 생성의 CORS 사전 요청에서 필요한 Header를 모두 허용한다")
    void allowsGuestDiaryCorsPreflight() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/guest/diaries")
                        .header(HttpHeaders.ORIGIN, FRONTEND_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "Content-Type, Idempotency-Key, X-XSRF-TOKEN"
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        FRONTEND_ORIGIN
                ))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
                        "true"
                ))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                .containsIgnoringCase("Content-Type")
                .containsIgnoringCase("Idempotency-Key")
                .containsIgnoringCase("X-XSRF-TOKEN");
    }

    @Test
    @DisplayName("멱등성 키가 없으면 게스트 생성 요청을 400으로 거절한다")
    void rejectMissingIdempotencyKey() {
        configureGuestSessionCookie();

        MockMvcResponse response = guestPostRequest()
                .contentType(ContentType.JSON)
                .body(validRequestBody())
                .post("/api/v1/guest/diaries");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code"))
                .isEqualTo("INVALID_IDEMPOTENCY_KEY");
    }

    @Test
    @DisplayName("허용하지 않은 게스트 일기 HTTP 메서드는 공개하지 않는다")
    void rejectUnpermittedGuestDiaryMethod() {
        MockMvcResponse response = guestPostRequest()
                .delete("/api/v1/guest/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private io.restassured.module.mockmvc.specification.MockMvcRequestSpecification guestPostRequest() {
        return RestAssuredMockMvc.given()
                .postProcessors(csrf())
                .cookie("guest_session", RAW_TOKEN);
    }

    private void configureGuestSessionCookie() {
        when(guestSessionCookieReader.read(any(HttpServletRequest.class)))
                .thenReturn(Optional.of(RAW_TOKEN));
    }

    private void configureImageUrl() {
        when(imageUrlProvider.createAccessUrl("generated/comic.png"))
                .thenReturn(new ImageAccessUrl(
                        URI.create("https://images.harudle.example/comic.png"),
                        IMAGE_EXPIRES_AT
                ));
    }

    private CreateGuestDiaryResult createGuestResult(boolean newlyCreated) {
        return new CreateGuestDiaryResult(
                DIARY_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                CREATED_AT,
                createGenerationResult(),
                newlyCreated
        );
    }

    private DiaryDetailResult createDetailResult() {
        return new DiaryDetailResult(
                DIARY_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                CREATED_AT,
                createGenerationResult()
        );
    }

    private DiaryGenerationResult createGenerationResult() {
        return new DiaryGenerationResult(
                GENERATION_ID,
                GenerationStatus.SUCCEEDED,
                "친구와 보낸 하루",
                "generated/comic.png",
                COMPLETED_AT
        );
    }

    private String validRequestBody() {
        return """
                {
                  "diaryDate": "2026-08-20",
                  "sourceText": "오늘 친구와 카페에 갔다."
                }
                """;
    }
}
