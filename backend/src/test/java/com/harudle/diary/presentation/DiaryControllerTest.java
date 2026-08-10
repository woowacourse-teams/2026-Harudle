package com.harudle.diary.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.GlobalExceptionHandler;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdFilter;
import com.harudle.common.security.ApiAccessDeniedHandler;
import com.harudle.common.security.ApiAuthenticationEntryPoint;
import com.harudle.common.security.ApiProblemResponseWriter;
import com.harudle.common.security.ApiSecurityConfiguration;
import com.harudle.diary.service.DiaryCreationService;
import com.harudle.diary.service.DiaryDeletionService;
import com.harudle.diary.service.DiaryQueryService;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryDayResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.diary.service.dto.DiarySummaryResult;
import com.harudle.diary.service.dto.DiaryTimelineResult;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.service.exception.DailyGenerationLimitExceededException;
import com.harudle.generation.service.port.ImageAccessUrl;
import com.harudle.generation.service.port.ImageStorageException;
import com.harudle.generation.service.port.ImageUrlProvider;
import io.restassured.http.ContentType;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DiaryController.class)
@Import({
        AuthenticatedUserIdResolver.class,
        DiaryResponseAssembler.class,
        GlobalExceptionHandler.class,
        ProblemDetailFactory.class,
        TraceIdFilter.class,
        ApiSecurityConfiguration.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiProblemResponseWriter.class
})
class DiaryControllerTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID DIARY_ID = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    private static final Instant CREATED_AT = Instant.parse("2026-08-06T11:10:23Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-06T11:11:42Z");
    private static final Instant IMAGE_EXPIRES_AT = Instant.parse("2026-08-06T11:20:23Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DiaryCreationService diaryCreationService;

    @MockitoBean
    private DiaryQueryService diaryQueryService;

    @MockitoBean
    private DiaryDeletionService diaryDeletionService;

    @MockitoBean
    private ImageUrlProvider imageUrlProvider;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("일기를 작성하고 4컷 이미지 생성 결과를 201로 반환한다")
    void createDiary() {
        when(diaryCreationService.create(any(CreateDiaryCommand.class)))
                .thenReturn(createDiaryResult(true));
        configureImageUrl();

        MockMvcResponse response = authenticatedRequest()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("""
                        {
                          "diaryDate": "2026-08-06",
                          "sourceText": "오늘 친구와 카페에 갔다."
                        }
                        """)
                .post("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.header("Location")).isEqualTo("/api/v1/diaries/" + DIARY_ID);
        assertThat(response.jsonPath().getString("id")).isEqualTo(DIARY_ID.toString());
        assertThat(response.jsonPath().getString("generation.imageUrl"))
                .isEqualTo("https://images.harudle.example/comic.png");
        assertThat(response.jsonPath().getInt("usage.remainingCount")).isEqualTo(2);
        assertThat(response.asString()).doesNotContain("imageObjectKey", "generated/comic.png");
    }

    @Test
    @DisplayName("성공한 멱등 재요청은 기존 결과를 200으로 반환한다")
    void createDiaryReturnsExistingResult() {
        when(diaryCreationService.create(any(CreateDiaryCommand.class)))
                .thenReturn(createDiaryResult(false));
        configureImageUrl();

        MockMvcResponse response = authenticatedRequest()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("""
                        {
                          "diaryDate": "2026-08-06",
                          "sourceText": "오늘 친구와 카페에 갔다."
                        }
                        """)
                .post("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header("Location")).isNull();
    }

    @Test
    @DisplayName("월의 모든 날짜와 일기 요약을 조회한다")
    void getTimeline() {
        DiarySummaryResult summary = new DiarySummaryResult(
                DIARY_ID,
                "친구와 보낸 하루",
                "generated/comic.png"
        );
        DiaryTimelineResult result = new DiaryTimelineResult(
                2026,
                8,
                List.of(new DiaryDayResult(DIARY_DATE, true, List.of(summary)))
        );
        when(diaryQueryService.getTimeline(USER_ID, 2026, 8)).thenReturn(result);
        configureImageUrl();

        MockMvcResponse response = authenticatedRequest()
                .queryParam("year", 2026)
                .queryParam("month", 8)
                .get("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("year")).isEqualTo(2026);
        assertThat(response.jsonPath().getString("days[0].items[0].thumbnailUrl"))
                .isEqualTo("https://images.harudle.example/comic.png");
        assertThat(response.asString()).doesNotContain("generated/comic.png");
    }

    @Test
    @DisplayName("본인 일기와 생성 결과 상세를 조회한다")
    void getDetail() {
        DiaryDetailResult result = new DiaryDetailResult(
                DIARY_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                CREATED_AT,
                createGenerationResult()
        );
        when(diaryQueryService.getDetail(USER_ID, DIARY_ID)).thenReturn(result);
        configureImageUrl();

        MockMvcResponse response = authenticatedRequest()
                .get("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("sourceText"))
                .isEqualTo("오늘 친구와 카페에 갔다.");
        assertThat(response.jsonPath().getString("generation.status")).isEqualTo("SUCCEEDED");
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
    @DisplayName("멱등성 키가 없으면 Problem Details를 반환한다")
    void createDiaryRejectsMissingIdempotencyKey() {
        MockMvcResponse response = authenticatedRequest()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "diaryDate": "2026-08-06",
                          "sourceText": "오늘 친구와 카페에 갔다."
                        }
                        """)
                .post("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_IDEMPOTENCY_KEY");
        assertThat(response.jsonPath().getString("traceId")).hasSize(32);
    }

    @Test
    @DisplayName("빈 일기 내용은 필드 검증 오류로 반환한다")
    void createDiaryRejectsBlankSourceText() {
        MockMvcResponse response = authenticatedRequest()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("""
                        {
                          "diaryDate": "2026-08-06",
                          "sourceText": "   "
                        }
                        """)
                .post("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        assertThat(response.jsonPath().getString("errors[0].field")).isEqualTo("sourceText");
    }

    @Test
    @DisplayName("300자를 초과한 일기 내용은 필드 검증 오류로 반환한다")
    void createDiaryRejectsSourceTextOverThreeHundredCharacters() {
        String sourceText = "🙂".repeat(301);
        String requestBody = """
                {
                  "diaryDate": "2026-08-06",
                  "sourceText": "%s"
                }
                """.formatted(sourceText);

        MockMvcResponse response = authenticatedRequest()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body(requestBody)
                .post("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        assertThat(response.jsonPath().getString("errors[0].field")).isEqualTo("sourceText");
    }

    @Test
    @DisplayName("이모지 300자는 하나의 코드 포인트 단위로 허용한다")
    void createDiaryAcceptsThreeHundredEmojiCharacters() {
        String sourceText = "🙂".repeat(300);
        String requestBody = """
                {
                  "diaryDate": "2026-08-06",
                  "sourceText": "%s"
                }
                """.formatted(sourceText);
        when(diaryCreationService.create(any(CreateDiaryCommand.class)))
                .thenReturn(createDiaryResult(true));
        configureImageUrl();

        MockMvcResponse response = authenticatedRequest()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body(requestBody)
                .post("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("축약 UUID 형식의 멱등성 키를 거부한다")
    void createDiaryRejectsAbbreviatedIdempotencyKey() {
        MockMvcResponse response = authenticatedRequest()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", "1-1-1-1-1")
                .body("""
                        {
                          "diaryDate": "2026-08-06",
                          "sourceText": "오늘 친구와 카페에 갔다."
                        }
                        """)
                .post("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_IDEMPOTENCY_KEY");
    }

    @Test
    @DisplayName("일일 생성 한도를 초과하면 다음 자정까지 재시도 시간을 반환한다")
    void createDiaryRejectsExceededDailyLimit() {
        when(diaryCreationService.create(any(CreateDiaryCommand.class)))
                .thenThrow(new DailyGenerationLimitExceededException(13_800));

        MockMvcResponse response = authenticatedRequest()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("""
                        {
                          "diaryDate": "2026-08-06",
                          "sourceText": "오늘 친구와 카페에 갔다."
                        }
                        """)
                .post("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.header("Retry-After")).isEqualTo("13800");
        assertThat(response.jsonPath().getString("code"))
                .isEqualTo("DAILY_GENERATION_LIMIT_EXCEEDED");
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
    @DisplayName("인증하지 않은 요청은 RFC 9457 오류를 반환한다")
    void rejectUnauthenticatedRequest() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .get("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
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
    @DisplayName("지원하지 않는 요청 미디어 타입은 415를 반환한다")
    void rejectUnsupportedContentType() {
        MockMvcResponse response = authenticatedRequest()
                .contentType(ContentType.TEXT)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .body("diaryDate=2026-08-06&sourceText=오늘")
                .post("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(415);
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
        DiaryDetailResult result = new DiaryDetailResult(
                DIARY_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                CREATED_AT,
                createGenerationResult()
        );
        when(diaryQueryService.getDetail(USER_ID, DIARY_ID)).thenReturn(result);
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

    private CreateDiaryResult createDiaryResult(boolean newlyCreated) {
        return new CreateDiaryResult(
                DIARY_ID,
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                CREATED_AT,
                createGenerationResult(),
                new GenerationUsage(DIARY_DATE, 1, 3),
                newlyCreated
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

    private void configureImageUrl() {
        when(imageUrlProvider.createAccessUrl("generated/comic.png"))
                .thenReturn(new ImageAccessUrl(
                        URI.create("https://images.harudle.example/comic.png"),
                        IMAGE_EXPIRES_AT
                ));
    }
}
