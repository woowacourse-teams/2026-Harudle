package com.harudle.share.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.harudle.common.config.TimeConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.security.ApiSecurityConfiguration;
import com.harudle.generation.service.port.ImageAccessUrl;
import com.harudle.generation.service.port.ImageUrlProvider;
import com.harudle.share.service.PublicShareResult;
import com.harudle.share.service.ShareLinkQueryService;
import com.harudle.share.service.exception.ShareNotFoundException;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicShareController.class)
@Import({
        PublicShareResponseAssembler.class,
        ProblemDetailFactory.class,
        ApiSecurityConfiguration.class,
        TimeConfiguration.class
})
class PublicShareControllerTest {

    private static final UUID SHARE_ID = UUID.fromString("06ed972e-0b79-4da0-9716-c9bd8faec85d");
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T11:10:23Z");
    private static final Instant IMAGE_URL_EXPIRES_AT = Instant.parse("2026-08-06T11:25:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShareLinkQueryService shareLinkQueryService;

    @MockitoBean
    private ImageUrlProvider imageUrlProvider;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("인증 없이 공개 공유 결과를 조회한다")
    void getPublicShareWithoutAuthentication() {
        when(shareLinkQueryService.getPublicShare(SHARE_ID)).thenReturn(new PublicShareResult(
                "친구와 보낸 카페 시간",
                LocalDate.of(2026, 8, 6),
                "generated/public-share.png",
                CREATED_AT
        ));
        when(imageUrlProvider.createAccessUrl("generated/public-share.png"))
                .thenReturn(new ImageAccessUrl(
                        URI.create("https://presigned-s3-url.example/image.png"),
                        IMAGE_URL_EXPIRES_AT
                ));

        MockMvcResponse response = RestAssuredMockMvc.given()
                .get("/api/v1/public/shares/{shareId}", SHARE_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("title")).isEqualTo("친구와 보낸 카페 시간");
        assertThat(response.jsonPath().getString("diaryDate")).isEqualTo("2026-08-06");
        assertThat(response.jsonPath().getString("imageUrl"))
                .isEqualTo("https://presigned-s3-url.example/image.png");
        assertThat(response.jsonPath().getString("imageUrlExpiresAt"))
                .isEqualTo("2026-08-06T20:25:00+09:00");
        assertThat(response.jsonPath().getString("createdAt"))
                .isEqualTo("2026-08-06T20:10:23+09:00");
        assertThat(response.asString()).doesNotContain(
                "sourceText",
                "email",
                "imageObjectKey",
                "generated/public-share.png"
        );
    }

    @Test
    @DisplayName("공유 링크가 없으면 404 SHARE_NOT_FOUND를 반환한다")
    void returnNotFoundWhenShareDoesNotExist() {
        when(shareLinkQueryService.getPublicShare(SHARE_ID))
                .thenThrow(new ShareNotFoundException());

        MockMvcResponse response = RestAssuredMockMvc.given()
                .get("/api/v1/public/shares/{shareId}", SHARE_ID);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.jsonPath().getString("code")).isEqualTo("SHARE_NOT_FOUND");
    }

    @Test
    @DisplayName("UUID 형식이 아닌 공유 ID는 검증 오류로 반환한다")
    void rejectInvalidShareId() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .get("/api/v1/public/shares/{shareId}", "invalid-share-id");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }
}
