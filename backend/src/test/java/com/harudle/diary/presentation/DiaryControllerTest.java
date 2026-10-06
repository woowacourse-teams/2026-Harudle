package com.harudle.diary.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

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
import com.harudle.diary.service.DiaryCreationService;
import com.harudle.diary.service.DiaryDeletionService;
import com.harudle.diary.service.DiaryQueryService;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryDayResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.diary.service.dto.DiaryStreakDayResult;
import com.harudle.diary.service.dto.DiaryStreakResult;
import com.harudle.diary.service.dto.DiarySummaryResult;
import com.harudle.diary.service.dto.DiaryTimelineResult;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.adapter.out.s3.R2FallbackImageUrlProvider;
import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.GenerationTokenUsage;
import com.harudle.generation.usage.domain.GenerationUsage;
import com.harudle.generation.usage.service.exception.DailyGenerationLimitExceededException;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.generation.diary.service.port.dto.BackupObjectMetadata;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageLookupBudget;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import io.restassured.http.ContentType;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.unit.DataSize;

@WebMvcTest(DiaryController.class)
@Import({
        AuthenticatedUserIdResolver.class,
        DiaryResponseAssembler.class,
        ApiExceptionLoggerTestConfiguration.class,
        ProblemDetailFactory.class,
        TraceIdConfiguration.class,
        CsrfConfiguration.class,
        SecurityConfig.class,
        TimeConfiguration.class
})
class DiaryControllerTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID DIARY_ID = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID SECOND_DIARY_ID = UUID.fromString("8c82a1c2-993f-41e9-8464-a8554b7620d7");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609");
    private static final String BACKUP_FOLDER = "harudle/generated/diary-images/dev/" + DIARY_ID + "/" + GENERATION_ID + "/";
    private static final String BACKUP_DETAIL_KEY = BACKUP_FOLDER + "image-960.webp";
    private static final String BACKUP_THUMBNAIL_KEY = BACKUP_FOLDER + "image-240.webp";
    private static final String HEALTHY_FOLDER = "harudle/generated/diary-images/dev/" + SECOND_DIARY_ID + "/" + GENERATION_ID + "/";
    private static final String HEALTHY_DETAIL_KEY = HEALTHY_FOLDER + "image-960.webp";
    private static final String HEALTHY_THUMBNAIL_KEY = HEALTHY_FOLDER + "image-240.webp";
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
        when(imageUrlProvider.forResponse()).thenCallRealMethod();
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
        assertThat(response.jsonPath().getInt("generation.tokenUsage.promptTokenCount"))
                .isEqualTo(120);
        assertThat(response.jsonPath().getInt("generation.tokenUsage.candidateTokenCount"))
                .isEqualTo(350);
        assertThat(response.jsonPath().getInt("generation.tokenUsage.thoughtTokenCount"))
                .isEqualTo(80);
        assertThat(response.jsonPath().getInt("generation.tokenUsage.totalTokenCount"))
                .isEqualTo(550);
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
        assertThat(response.jsonPath().getString("days[0].items[0].title"))
                .isEqualTo("친구와 보낸 하루");
        assertThat(response.jsonPath().getString("days[0].items[0].thumbnailUrl"))
                .isEqualTo("https://images.harudle.example/comic.png");
        assertThat(response.jsonPath().getString("days[0].items[1].title"))
                .isEqualTo("산책으로 마무리한 하루");
        assertThat(response.jsonPath().getString("days[0].items[1].thumbnailUrl"))
                .isEqualTo("https://images.harudle.example/second-comic.png");
        assertThat(response.asString()).doesNotContain("generated/comic.png");
        assertThat(response.asString()).doesNotContain("generated/second-comic.png");
    }

    @Test
    @DisplayName("WebP 일기 타임라인에서 썸네일 이미지를 사용한다")
    void newWebpTimelineUsesThumbnailVariant() {
        DiarySummaryResult summary = new DiarySummaryResult(
                DIARY_ID, "새 일기", "generated/diary-images/id/image-960.webp"
        );
        when(diaryQueryService.getTimeline(USER_ID, 2026, 8))
                .thenReturn(new DiaryTimelineResult(
                        2026, 8, List.of(new DiaryDayResult(DIARY_DATE, List.of(summary)))
                ));
        when(imageUrlProvider.createAccessUrl("generated/diary-images/id/image-240.webp"))
                .thenReturn(new ImageAccessUrl(
                        URI.create("https://images.harudle.example/image-240.webp"), IMAGE_EXPIRES_AT
                ));

        MockMvcResponse response = authenticatedRequest()
                .queryParam("year", 2026)
                .queryParam("month", 8)
                .get("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("days[0].items[0].thumbnailUrl"))
                .isEqualTo("https://images.harudle.example/image-240.webp");
    }

    @Test
    @DisplayName("현재 streak와 삭제되어 콘텐츠가 없는 연속 날짜를 조회한다")
    void getCurrentStreak() {
        DiarySummaryResult summary = new DiarySummaryResult(
                DIARY_ID,
                "친구와 보낸 하루",
                "generated/comic.png"
        );
        DiaryStreakResult result = new DiaryStreakResult(
                true,
                List.of(
                        new DiaryStreakDayResult(DIARY_DATE, List.of(summary)),
                        new DiaryStreakDayResult(DIARY_DATE.minusDays(1), List.of())
                )
        );
        when(diaryQueryService.getCurrentStreak(USER_ID)).thenReturn(result);
        configureImageUrl();

        MockMvcResponse response = authenticatedRequest()
                .get("/api/v1/diaries/current-streak");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("streakCount")).isEqualTo(2);
        assertThat(response.jsonPath().getBoolean("recordedToday")).isTrue();
        assertThat(response.jsonPath().getString("days[0].date"))
                .isEqualTo(DIARY_DATE.toString());
        assertThat(response.jsonPath().getString("days[0].items[0].id"))
                .isEqualTo(DIARY_ID.toString());
        assertThat(response.jsonPath().getString("days[0].items[0].thumbnailUrl"))
                .isEqualTo("https://images.harudle.example/comic.png");
        assertThat(response.jsonPath().getString("days[1].date"))
                .isEqualTo(DIARY_DATE.minusDays(1).toString());
        assertThat(response.jsonPath().getList("days[1].items")).isEmpty();
        assertThat(response.asString()).doesNotContain("imageObjectKey", "generated/comic.png");
    }

    @Test
    @DisplayName("현재 streak가 없으면 빈 날짜 목록을 반환한다")
    void getEmptyCurrentStreak() {
        when(diaryQueryService.getCurrentStreak(USER_ID))
                .thenReturn(new DiaryStreakResult(false, List.of()));

        MockMvcResponse response = authenticatedRequest()
                .get("/api/v1/diaries/current-streak");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("streakCount")).isZero();
        assertThat(response.jsonPath().getBoolean("recordedToday")).isFalse();
        assertThat(response.jsonPath().getList("days")).isEmpty();
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
                .postProcessors(csrf())
                .header("Authorization", "Bearer valid-access-token")
                .delete("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(204);
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
    @DisplayName("생성 기능이 구성되지 않았으면 503 Problem Details를 반환한다")
    void createDiaryRejectsUnavailableGeneration() {
        when(diaryCreationService.create(any(CreateDiaryCommand.class)))
                .thenThrow(GenerationUnavailableException.adaptersNotConfigured());

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

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.jsonPath().getString("code")).isEqualTo("GENERATION_UNAVAILABLE");
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
        verifyNoInteractions(imageUrlProvider);
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
        verifyNoInteractions(imageUrlProvider);
    }

    @Test
    @DisplayName("인증하지 않은 요청은 Bearer challenge를 반환한다")
    void rejectUnauthenticatedRequest() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .get("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.header("WWW-Authenticate")).startsWith("Bearer");
        assertThat(response.jsonPath().getString("type")).isEqualTo("urn:harudle:problem:unauthorized");
        assertThat(response.jsonPath().getString("code")).isEqualTo("UNAUTHORIZED");
        assertThat(response.jsonPath().getString("traceId")).hasSize(32);
        verifyNoInteractions(imageUrlProvider);
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

    @Test
    @DisplayName("S3 누락 시 같은 상세 응답 필드에 R2 원본 URL과 만료 시각을 반환한다")
    void detailReturnsR2OriginalWithoutChangingResponseContract() {
        configureFallbackDiary();
        BackupObjectStorage backup = configureR2Fallback();
        String originalKey = BACKUP_FOLDER + "image.png";
        when(backup.findMetadata(originalKey)).thenReturn(Optional.of(
                new BackupObjectMetadata(originalKey, MediaType.IMAGE_PNG, 123, null)));
        when(backup.createAccessUrl(originalKey)).thenReturn(new ImageAccessUrl(
                URI.create("https://backup.example/image.png?signature=test"), IMAGE_EXPIRES_AT));

        MockMvcResponse response = authenticatedRequest().get("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("generation.imageUrl"))
                .isEqualTo("https://backup.example/image.png?signature=test");
        assertThat(response.jsonPath().getString("generation.imageUrlExpiresAt"))
                .isEqualTo("2026-08-06T20:20:23+09:00");
        assertThat(response.jsonPath().getString("generation.status")).isEqualTo("SUCCEEDED");
        assertThat(response.asString()).doesNotContain("imageObjectKey", BACKUP_DETAIL_KEY);
        verify(backup).createAccessUrl(originalKey);
    }

    @Test
    @DisplayName("S3 썸네일 누락 시 월간 응답의 thumbnailUrl에 R2 JPG 원본을 반환한다")
    void timelineReturnsR2OriginalForMissingThumbnail() {
        when(diaryQueryService.getTimeline(USER_ID, 2026, 8)).thenReturn(new DiaryTimelineResult(
                2026, 8, List.of(new DiaryDayResult(DIARY_DATE,
                        List.of(new DiarySummaryResult(DIARY_ID, "새 일기", BACKUP_DETAIL_KEY))))));
        BackupObjectStorage backup = configureR2Fallback();
        String originalKey = BACKUP_FOLDER + "image.jpg";
        when(backup.findMetadata(originalKey)).thenReturn(Optional.of(
                new BackupObjectMetadata(originalKey, MediaType.IMAGE_JPEG, 123, null)));
        when(backup.createAccessUrl(originalKey)).thenReturn(new ImageAccessUrl(
                URI.create("https://backup.example/image.jpg?signature=test"), IMAGE_EXPIRES_AT));

        MockMvcResponse response = authenticatedRequest().queryParam("year", 2026).queryParam("month", 8)
                .get("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("days[0].items[0].thumbnailUrl"))
                .isEqualTo("https://backup.example/image.jpg?signature=test");
        verify(imageUrlProvider).forResponse();
        verify(backup).createAccessUrl(originalKey);
    }

    @Test
    @DisplayName("S3와 R2 원본이 모두 없더라도 상세 응답에 기존 S3 URL을 반환한다")
    void detailReturnsS3UrlWhenBackupIsMissing() {
        configureFallbackDiary();
        configureR2Fallback();

        MockMvcResponse response = authenticatedRequest().get("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("generation.imageUrl")).isEqualTo(sourceUrlFor(BACKUP_DETAIL_KEY));
        assertThat(response.jsonPath().getString("generation.imageUrlExpiresAt"))
                .isEqualTo("2026-08-06T20:20:23+09:00");
    }

    @Test
    @DisplayName("R2 권한 오류가 발생해도 상세 응답에 기존 S3 URL을 반환한다")
    void detailReturnsS3UrlWhenR2AccessFails() {
        configureFallbackDiary();
        BackupObjectStorage backup = configureR2Fallback();
        when(backup.findMetadata(BACKUP_FOLDER + "image.png")).thenThrow(new BackupStorageException(
                BackupStorageException.FailureType.AUTHORIZATION_ERROR, new RuntimeException("private-secret")));

        MockMvcResponse response = authenticatedRequest().get("/api/v1/diaries/{diaryId}", DIARY_ID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("generation.imageUrl")).isEqualTo(sourceUrlFor(BACKUP_DETAIL_KEY));
        assertThat(response.asString()).doesNotContain("private-secret");
    }

    @ParameterizedTest
    @CsvSource({"MISSING,MISSING", "ERROR,MISSING", "MISSING,ERROR", "ERROR,ERROR"})
    @DisplayName("한 썸네일의 S3 누락·오류와 R2 백업 부재·오류가 월간 목록 전체를 실패시키지 않는다")
    void timelineKeepsEveryDiaryWhenOneImageCannotUseBackup(String sourceResult, String backupResult) {
        when(diaryQueryService.getTimeline(USER_ID, 2026, 8)).thenReturn(new DiaryTimelineResult(
                2026, 8, List.of(new DiaryDayResult(DIARY_DATE, List.of(
                        new DiarySummaryResult(DIARY_ID, "누락된 이미지", BACKUP_DETAIL_KEY),
                        new DiarySummaryResult(SECOND_DIARY_ID, "정상 이미지", HEALTHY_DETAIL_KEY))))));
        ImageStorage source = configureMixedImageFallback(sourceResult, backupResult);

        MockMvcResponse response = authenticatedRequest().queryParam("year", 2026).queryParam("month", 8)
                .get("/api/v1/diaries");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getList("days[0].items.id", String.class))
                .containsExactly(DIARY_ID.toString(), SECOND_DIARY_ID.toString());
        assertThat(response.jsonPath().getString("days[0].items[0].thumbnailUrl"))
                .isEqualTo(sourceUrlFor(BACKUP_THUMBNAIL_KEY));
        assertThat(response.jsonPath().getString("days[0].items[1].thumbnailUrl"))
                .isEqualTo(sourceUrlFor(HEALTHY_THUMBNAIL_KEY));
        verify(source).exists(BACKUP_THUMBNAIL_KEY);
        verify(source).exists(HEALTHY_THUMBNAIL_KEY);
    }

    @ParameterizedTest
    @CsvSource({"MISSING,MISSING", "ERROR,MISSING", "MISSING,ERROR", "ERROR,ERROR"})
    @DisplayName("한 썸네일의 S3 누락·오류와 R2 백업 부재·오류가 연속 기록 응답을 실패시키지 않는다")
    void streakKeepsEveryDayWhenOneImageCannotUseBackup(String sourceResult, String backupResult) {
        when(diaryQueryService.getCurrentStreak(USER_ID)).thenReturn(new DiaryStreakResult(true, List.of(
                new DiaryStreakDayResult(DIARY_DATE,
                        List.of(new DiarySummaryResult(DIARY_ID, "누락된 이미지", BACKUP_DETAIL_KEY))),
                new DiaryStreakDayResult(DIARY_DATE.minusDays(1),
                        List.of(new DiarySummaryResult(SECOND_DIARY_ID, "정상 이미지", HEALTHY_DETAIL_KEY))))));
        ImageStorage source = configureMixedImageFallback(sourceResult, backupResult);

        MockMvcResponse response = authenticatedRequest().get("/api/v1/diaries/current-streak");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("streakCount")).isEqualTo(2);
        assertThat(response.jsonPath().getBoolean("recordedToday")).isTrue();
        assertThat(response.jsonPath().getString("days[0].items[0].id")).isEqualTo(DIARY_ID.toString());
        assertThat(response.jsonPath().getString("days[1].items[0].id")).isEqualTo(SECOND_DIARY_ID.toString());
        assertThat(response.jsonPath().getString("days[0].items[0].thumbnailUrl"))
                .isEqualTo(sourceUrlFor(BACKUP_THUMBNAIL_KEY));
        assertThat(response.jsonPath().getString("days[1].items[0].thumbnailUrl"))
                .isEqualTo(sourceUrlFor(HEALTHY_THUMBNAIL_KEY));
        verify(source).exists(BACKUP_THUMBNAIL_KEY);
        verify(source).exists(HEALTHY_THUMBNAIL_KEY);
    }

    private ImageStorage configureMixedImageFallback(String sourceResult, String backupResult) {
        ImageStorage source = mock(ImageStorage.class);
        when(source.exists(HEALTHY_THUMBNAIL_KEY)).thenReturn(true);
        if ("ERROR".equals(sourceResult)) {
            when(source.exists(BACKUP_THUMBNAIL_KEY)).thenThrow(new ImageStorageException("S3 HEAD timeout", null,
                    ImageStorageException.DiagnosticType.CLIENT_ERROR));
        }
        BackupObjectStorage backup = configureR2Fallback(source);
        if ("ERROR".equals(backupResult)) {
            when(backup.findMetadata(BACKUP_FOLDER + "image.png")).thenThrow(new BackupStorageException(
                    BackupStorageException.FailureType.AUTHORIZATION_ERROR, new RuntimeException("R2 access denied")));
        }
        return source;
    }

    private void configureFallbackDiary() {
        when(diaryQueryService.getDetail(USER_ID, DIARY_ID)).thenReturn(new DiaryDetailResult(
                DIARY_ID, DIARY_DATE, "오늘 친구와 카페에 갔다.", CREATED_AT,
                new DiaryGenerationResult(GENERATION_ID, GenerationStatus.SUCCEEDED, "새 일기",
                        BACKUP_DETAIL_KEY, COMPLETED_AT)));
    }

    private BackupObjectStorage configureR2Fallback() {
        return configureR2Fallback(mock(ImageStorage.class));
    }

    private BackupObjectStorage configureR2Fallback(ImageStorage source) {
        BackupObjectStorage backup = mock(BackupObjectStorage.class);
        S3StorageProperties s3 = new S3StorageProperties("test-source", "ap-northeast-2", "dev",
                "harudle/generated/diary-images/dev", "harudle/references/generation/dev",
                DataSize.ofMegabytes(20), Duration.ofMinutes(15));
        R2StorageProperties r2 = new R2StorageProperties(true, "dev", URI.create("https://backup.example"),
                "test-backup", "fake-key", "fake-secret", Duration.ofMinutes(15), DataSize.ofMegabytes(20), Duration.ofSeconds(2));
        ImageUrlProvider primary = mock(ImageUrlProvider.class);
        when(primary.createAccessUrl(anyString())).thenAnswer(invocation -> new ImageAccessUrl(
                URI.create(sourceUrlFor(invocation.getArgument(0))), IMAGE_EXPIRES_AT));
        ImageUrlProvider fallback = new R2FallbackImageUrlProvider(primary, source, backup, s3, r2, () -> 0);
        when(source.exists(anyString(), any(ImageLookupBudget.class)))
                .thenAnswer(invocation -> source.exists(invocation.getArgument(0)));
        when(backup.findMetadata(anyString(), any(ImageLookupBudget.class)))
                .thenAnswer(invocation -> backup.findMetadata(invocation.getArgument(0)));
        when(imageUrlProvider.forResponse()).thenAnswer(invocation -> fallback.forResponse());
        when(imageUrlProvider.createAccessUrl(anyString())).thenAnswer(
                invocation -> fallback.createAccessUrl(invocation.getArgument(0)));
        return backup;
    }

    private String sourceUrlFor(String key) {
        return "https://source.example/" + key + "?signature=test";
    }

    private io.restassured.module.mockmvc.specification.MockMvcRequestSpecification authenticatedRequest() {
        return RestAssuredMockMvc.given().postProcessors(
                user(USER_ID.toString()),
                csrf()
        );
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
                COMPLETED_AT,
                new GenerationTokenUsage(120, 350, 80, 550)
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
