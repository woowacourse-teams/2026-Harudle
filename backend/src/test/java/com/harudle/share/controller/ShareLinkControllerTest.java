package com.harudle.share.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.config.TimeConfiguration;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.share.configuration.ShareConfiguration;
import com.harudle.share.service.ShareLinkCreationResult;
import com.harudle.share.service.ShareLinkService;
import com.harudle.share.service.exception.ShareGenerationFailedException;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ShareLinkController.class)
@Import({
        AuthenticatedUserIdResolver.class,
        ShareLinkResponseAssembler.class,
        ShareConfiguration.class,
        ApiExceptionLoggerTestConfiguration.class,
        ProblemDetailFactory.class,
        TraceIdConfiguration.class,
        CsrfConfiguration.class,
        SecurityConfig.class,
        TimeConfiguration.class
})
@TestPropertySource(properties = "harudle.share.public-base-url=https://harudle.example/shares")
class ShareLinkControllerTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID DIARY_ID = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID SHARE_ID = UUID.fromString("06ed972e-0b79-4da0-9716-c9bd8faec85d");
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T11:15:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShareLinkService shareLinkService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

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
    @DisplayName("공유 링크를 새로 생성하고 201과 완성된 공유 URL을 반환한다")
    void createShareLink() {
        when(shareLinkService.createOrGet(USER_ID, DIARY_ID))
                .thenReturn(new ShareLinkCreationResult(SHARE_ID, CREATED_AT, true));

        MockMvcResponse response = authenticatedRequest()
                .put("/api/v1/diaries/{diaryId}/share-link", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.jsonPath().getString("shareId")).isEqualTo(SHARE_ID.toString());
        assertThat(response.jsonPath().getString("shareUrl"))
                .isEqualTo("https://harudle.example/shares/" + SHARE_ID);
        assertThat(response.jsonPath().getString("createdAt"))
                .isEqualTo("2026-08-06T20:15:00+09:00");
        verify(shareLinkService).createOrGet(USER_ID, DIARY_ID);
    }

    @Test
    @DisplayName("기존 공유 링크를 200으로 반환한다")
    void returnExistingShareLink() {
        when(shareLinkService.createOrGet(USER_ID, DIARY_ID))
                .thenReturn(new ShareLinkCreationResult(SHARE_ID, CREATED_AT, false));

        MockMvcResponse response = authenticatedRequest()
                .put("/api/v1/diaries/{diaryId}/share-link", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("shareId")).isEqualTo(SHARE_ID.toString());
        assertThat(response.jsonPath().getString("shareUrl"))
                .isEqualTo("https://harudle.example/shares/" + SHARE_ID);
    }

    @Test
    @DisplayName("그림일기 생성이 실패했으면 409 GENERATION_FAILED를 반환한다")
    void rejectFailedGeneration() {
        when(shareLinkService.createOrGet(USER_ID, DIARY_ID))
                .thenThrow(new ShareGenerationFailedException());

        MockMvcResponse response = authenticatedRequest()
                .put("/api/v1/diaries/{diaryId}/share-link", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.jsonPath().getString("code")).isEqualTo("GENERATION_FAILED");
    }

    @Test
    @DisplayName("인증하지 않은 사용자는 공유 링크를 생성할 수 없다")
    void rejectUnauthenticatedRequest() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .postProcessors(csrf())
                .put("/api/v1/diaries/{diaryId}/share-link", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.header("WWW-Authenticate")).startsWith("Bearer");
    }

    @Test
    @DisplayName("UUID 형식이 아닌 일기 ID는 검증 오류로 반환한다")
    void rejectInvalidDiaryId() {
        MockMvcResponse response = authenticatedRequest()
                .put("/api/v1/diaries/{diaryId}/share-link", "invalid-diary-id");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }

    private io.restassured.module.mockmvc.specification.MockMvcRequestSpecification authenticatedRequest() {
        return RestAssuredMockMvc.given().postProcessors(
                user(USER_ID.toString()),
                csrf()
        );
    }
}
