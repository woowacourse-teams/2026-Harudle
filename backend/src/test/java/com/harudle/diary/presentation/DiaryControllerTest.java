package com.harudle.diary.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.config.TimeConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.security.ApiSecurityConfiguration;
import com.harudle.diary.service.DiaryDeletionService;
import com.harudle.diary.service.DiaryQueryService;
import com.harudle.diary.service.dto.DiaryDayResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.diary.service.dto.DiarySummaryResult;
import com.harudle.diary.service.dto.DiaryTimelineResult;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.service.port.ImageAccessUrl;
import com.harudle.generation.service.port.ImageStorageException;
import com.harudle.generation.service.port.ImageUrlProvider;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DiaryController.class)
@Import({
        AuthenticatedUserIdResolver.class,
        DiaryResponseAssembler.class,
        ProblemDetailFactory.class,
        ApiSecurityConfiguration.class,
        TimeConfiguration.class
})
class DiaryControllerTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID DIARY_ID = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID SECOND_DIARY_ID = UUID.fromString("8c82a1c2-993f-41e9-8464-a8554b7620d7");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T11:10:23Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-06T11:11:42Z");
    private static final Instant IMAGE_EXPIRES_AT = Instant.parse("2026-08-06T11:20:23Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DiaryQueryService diaryQueryService;

    @MockitoBean
    private DiaryDeletionService diaryDeletionService;

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
    @DisplayName("월의 모든 날짜와 일기 요약을 조회한다")
    void getTimeline() {
        DiarySummaryResult summary = new DiarySummaryResult(
                DIARY_ID,
                "친구와 보낸 하루",
                "generated/comic.png"
        );
        DiarySummaryResult secondSummary = new DiarySummaryResult(
                SECOND_DIARY_ID,
                "산책으로 마무리한 하루",
                "generated/second-comic.png"
        );
        DiaryTimelineResult result = new DiaryTimelineResult(
                2026,
                8,
                List.of(new DiaryDayResult(DIARY_DATE, List.of(summary, secondSummary)))
        );
        when(diaryQueryService.getTimeline(USER_ID, 2026, 8)).thenReturn(result);
        configureImageUrl();
        when(imageUrlProvider.createAccessUrl("generated/second-comic.png"))
                .thenReturn(new ImageAccessUrl(
                        URI.create("https://images.harudle.example/second-comic.png"),
                        IMAGE_EXPIRES_AT
                ));

        MockMvcResponse response = authenticatedRequest()
                .queryParam("year", 2026)
                .queryParam("month", 8)
                .get("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("year")).isEqualTo(2026);
        assertThat(response.jsonPath().getList("days[0].items.id", String.class))
                .containsExactly(DIARY_ID.toString(), SECOND_DIARY_ID.toString());
        assertThat(response.jsonPath().getString("days[0].items[0].thumbnailUrl"))
                .isEqualTo("https://images.harudle.example/comic.png");
        assertThat(response.jsonPath().getString("days[0].items[1].title"))
                .isEqualTo("산책으로 마무리한 하루");
        assertThat(response.jsonPath().getString("days[0].items[1].thumbnailUrl"))
                .isEqualTo("https://images.harudle.example/second-comic.png");
        assertThat(response.asString()).doesNotContain("generated/comic.png");
    }

    @Test
    @DisplayName("본인 일기와 생성 결과 상세를 조회한다")
    void getDetail() {
        when(diaryQueryService.getDetail(USER_ID, DIARY_ID)).thenReturn(createDetailResult());
        configureImageUrl();

        MockMvcResponse response = authenticatedRequest()
                .get("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("sourceText"))
                .isEqualTo("오늘 친구와 카페에 갔다.");
        assertThat(response.jsonPath().getString("createdAt"))
                .isEqualTo("2026-08-06T20:10:23+09:00");
        assertThat(response.jsonPath().getString("generation.status")).isEqualTo("SUCCEEDED");
        assertThat(response.jsonPath().getString("generation.completedAt"))
                .isEqualTo("2026-08-06T20:11:42+09:00");
        assertThat(response.asString()).doesNotContain("generated/comic.png");
    }

    @Test
    @DisplayName("본인 일기를 삭제하고 204를 반환한다")
    void deleteDiary() {
        MockMvcResponse response = authenticatedRequest()
                .delete("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.asString()).isEmpty();
        verify(diaryDeletionService).delete(USER_ID, DIARY_ID);
    }

    @Test
    @DisplayName("Bearer Access Token의 subject로 인증한 사용자가 일기를 삭제한다")
    void deleteDiaryWithBearerToken() {
        when(jwtDecoder.decode("valid-access-token")).thenReturn(createJwt());

        MockMvcResponse response = RestAssuredMockMvc.given()
                .header("Authorization", "Bearer valid-access-token")
                .delete("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(204);
        verify(diaryDeletionService).delete(USER_ID, DIARY_ID);
    }

    @Test
    @DisplayName("월 조회 범위를 벗어난 쿼리는 검증 오류로 반환한다")
    void getTimelineRejectsInvalidMonth() {
        MockMvcResponse response = authenticatedRequest()
                .queryParam("year", 2026)
                .queryParam("month", 13)
                .get("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("존재하지 않는 일기는 404 Problem Details로 반환한다")
    void getDetailReturnsNotFound() {
        when(diaryQueryService.getDetail(USER_ID, DIARY_ID)).thenThrow(new DiaryNotFoundException());

        MockMvcResponse response = authenticatedRequest()
                .get("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.jsonPath().getString("code")).isEqualTo("DIARY_NOT_FOUND");
    }

    @Test
    @DisplayName("다른 사용자의 일기는 403 Problem Details로 반환한다")
    void getDetailReturnsForbidden() {
        when(diaryQueryService.getDetail(USER_ID, DIARY_ID))
                .thenThrow(new DiaryAccessDeniedException());

        MockMvcResponse response = authenticatedRequest()
                .get("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.jsonPath().getString("code")).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("인증하지 않은 요청은 RFC 9457 오류를 반환한다")
    void rejectUnauthenticatedRequest() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .get("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.header("WWW-Authenticate")).startsWith("Bearer");
        assertThat(response.jsonPath().getString("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("지원하지 않는 HTTP 메서드는 Allow 헤더와 405를 반환한다")
    void rejectUnsupportedHttpMethod() {
        MockMvcResponse response = authenticatedRequest()
                .post("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.header("Allow")).contains("GET", "DELETE");
    }

    @Test
    @DisplayName("존재하지 않는 API는 404를 유지한다")
    void returnNotFoundForUnknownApi() {
        MockMvcResponse response = authenticatedRequest()
                .get("/api/v1/unknown");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("서버 내부 불변식 오류는 요청 검증 오류로 오분류하지 않는다")
    void returnInternalServerErrorForIllegalState() {
        when(diaryQueryService.getDetail(USER_ID, DIARY_ID))
                .thenThrow(new IllegalArgumentException("서버 내부 불변식 오류"));

        MockMvcResponse response = authenticatedRequest()
                .get("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.jsonPath().getString("code")).isEqualTo("INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("이미지 URL 발급 실패는 이미지 저장소 오류로 반환한다")
    void returnStorageErrorWhenImageUrlCreationFails() {
        when(diaryQueryService.getDetail(USER_ID, DIARY_ID)).thenReturn(createDetailResult());
        when(imageUrlProvider.createAccessUrl("generated/comic.png"))
                .thenThrow(new ImageStorageException("이미지 URL 발급 실패"));

        MockMvcResponse response = authenticatedRequest()
                .get("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.jsonPath().getString("code")).isEqualTo("IMAGE_STORAGE_ERROR");
    }

    private io.restassured.module.mockmvc.specification.MockMvcRequestSpecification authenticatedRequest() {
        return RestAssuredMockMvc.given().postProcessors(user(USER_ID.toString()));
    }

    private DiaryDetailResult createDetailResult() {
        return new DiaryDetailResult(
                DIARY_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                CREATED_AT,
                new DiaryGenerationResult(
                        GENERATION_ID,
                        GenerationStatus.SUCCEEDED,
                        "친구와 보낸 하루",
                        "generated/comic.png",
                        COMPLETED_AT
                )
        );
    }

    private Jwt createJwt() {
        return Jwt.withTokenValue("valid-access-token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(CREATED_AT.minusSeconds(60))
                .expiresAt(CREATED_AT.plusSeconds(600))
                .build();
    }

    private void configureImageUrl() {
        when(imageUrlProvider.createAccessUrl("generated/comic.png"))
                .thenReturn(new ImageAccessUrl(
                        URI.create("https://images.harudle.example/comic.png"),
                        IMAGE_EXPIRES_AT
                ));
    }
}
