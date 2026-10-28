package com.harudle.admin.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.admin.query.AdminReportDetail;
import com.harudle.admin.query.AdminReportPage;
import com.harudle.admin.query.AdminReportSnapshot;
import com.harudle.admin.query.AdminReportStatus;
import com.harudle.admin.query.AdminReportSummary;
import com.harudle.admin.query.AdminReportTarget;
import com.harudle.admin.service.AdminReportQueryService;
import com.harudle.admin.service.AdminReportReview;
import com.harudle.admin.service.AdminReportReviewService;
import com.harudle.admin.service.exception.AdminReportNotFoundException;
import com.harudle.admin.service.exception.AdminReportReviewConflictException;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportTargetType;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

@WebMvcTest(AdminReportController.class)
@Import({AuthenticatedUserIdResolver.class, ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class,
        TraceIdConfiguration.class, CsrfConfiguration.class, SecurityConfig.class})
class AdminReportControllerTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID REPORT = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID AUTHOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
    private static final String PATH = "/api/v1/admin/reports";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AdminReportQueryService queries;
    @MockitoBean private AdminReportReviewService reviews;
    @MockitoBean private ImageUrlProvider imageUrls;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository users;
    @MockitoBean private OAuthLoginSuccessHandler successHandler;
    @MockitoBean private OAuthLoginFailureHandler failureHandler;
    @MockitoBean private ClientRegistrationRepository registrations;

    private User admin;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
        when(jwtDecoder.decode("admin-token")).thenReturn(Jwt.withTokenValue("admin-token")
                .header("alg", "HS256").subject(ADMIN.toString()).issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(1800)).build());
        admin = mock(User.class);
        when(admin.isAdmin()).thenReturn(true);
        when(users.findById(ADMIN)).thenReturn(Optional.of(admin));
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("접수 상태와 기본 페이지로 조회하고 기존 관리자 페이지 응답 구조를 사용한다")
    void listsReceivedReportsWithDefaultPage() {
        var summary = new AdminReportSummary(REPORT, null, ReportTargetType.FEED, TARGET, AUTHOR,
                ReportReason.SPAM, AdminReportStatus.RECEIVED, NOW, null);
        when(queries.search(AdminReportStatus.RECEIVED, 0, 20))
                .thenReturn(new AdminReportPage(List.of(summary), 21));

        var response = adminRequest().get(PATH);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.jsonPath().getMap("$")).containsOnlyKeys(
                "content", "page", "size", "totalElements", "totalPages", "hasNext");
        assertThat(response.jsonPath().getString("content[0].id")).isEqualTo(REPORT.toString());
        assertThat(response.jsonPath().getString("content[0].reporterId")).isNull();
        assertThat(response.jsonPath().getInt("totalPages")).isEqualTo(2);
        assertThat(response.jsonPath().getBoolean("hasNext")).isTrue();
        assertThat(response.asString()).doesNotContain("description");
    }

    @Test
    @DisplayName("원본 없는 신고도 상세를 반환하고 이미지 발급은 시도하지 않는다")
    void readsMissingTargetWithoutImageLookup() {
        when(queries.findDetail(REPORT)).thenReturn(new AdminReportDetail(
                snapshot(AdminReportStatus.RECEIVED), AdminReportTarget.missing()));
        var response = adminRequest().get(PATH + "/" + REPORT);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("report.description")).isEqualTo("신고 상세");
        assertThat(response.jsonPath().getBoolean("target.exists")).isFalse();
        assertThat(response.jsonPath().getString("target.imageUrl")).isNull();
        assertThat(response.asString()).doesNotContain("sourceText", "source_text", "imageObjectKey");
        verifyNoInteractions(imageUrls);
    }

    @Test
    @DisplayName("삭제된 댓글의 심사 내용과 부모 피드를 반환하며 이미지 URL은 조회 뒤 조립한다")
    void includesDeletedTargetEvidenceWithoutPrivateDiaryText() {
        var target = new AdminReportTarget(true, AUTHOR, "작성자", true, true, false,
                true, false, false, TARGET, "공개 제목", "generated/review.webp", "삭제된 댓글 내용");
        when(queries.findDetail(REPORT)).thenReturn(new AdminReportDetail(
                snapshot(AdminReportStatus.RECEIVED), target));
        when(imageUrls.forRead()).thenReturn(imageUrls);
        when(imageUrls.createAccessUrl("generated/review.webp"))
                .thenReturn(new ImageAccessUrl(URI.create("https://images.example.test/review"), NOW.plusSeconds(900)));
        var response = adminRequest().get(PATH + "/" + REPORT);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("target.commentContent")).isEqualTo("삭제된 댓글 내용");
        assertThat(response.jsonPath().getBoolean("target.hidden")).isTrue();
        assertThat(response.jsonPath().getBoolean("target.publiclyVisible")).isFalse();
        assertThat(response.jsonPath().getString("target.feedId")).isEqualTo(TARGET.toString());
        assertThat(response.jsonPath().getString("target.imageUrl"))
                .isEqualTo("https://images.example.test/review");
        assertThat(response.asString()).doesNotContain("sourceText", "source_text", "generated/review.webp");
    }

    @Test
    @DisplayName("종결 처리자는 본문의 위조 ID가 아니라 실제 인증 관리자이며 사유는 정규화한다")
    void reviewsUsingAuthenticatedAdministrator() {
        when(reviews.review(eq(ADMIN), eq(REPORT), any())).thenReturn(snapshot(AdminReportStatus.RESOLVED));
        var response = adminRequest().body("""
                {"status":"RESOLVED","reason":"  처리 완료  ","reviewedBy":"%s"}
                """.formatted(UUID.randomUUID())).put(PATH + "/" + REPORT + "/review");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.jsonPath().getString("status")).isEqualTo("RESOLVED");
        verify(reviews).review(ADMIN, REPORT, new AdminReportReview(AdminReportStatus.RESOLVED, "처리 완료"));
    }

    @ParameterizedTest
    @MethodSource("invalidReviewBodies")
    @DisplayName("빈 사유나 잘못된 상태와 문자 입력은 심사를 실행하지 않는다")
    void rejectsInvalidReviewBody(String body) {
        var response = adminRequest().body(body).put(PATH + "/" + REPORT + "/review");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.asString()).doesNotContain("비밀");
        verifyNoInteractions(reviews);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?size=0", "?size=101", "?page=-1", "?page=21474837", "?status=UNKNOWN"})
    @DisplayName("잘못된 필터와 페이지 크기는 서비스 실행 전에 거절한다")
    void rejectsInvalidListParameters(String query) {
        assertThat(adminRequest().get(PATH + query).statusCode()).isEqualTo(400);
        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("로그인하지 않았거나 잘못된 토큰이면 관리자 신고 데이터에 접근하지 못한다")
    void rejectsAnonymousAndInvalidBearer() {
        assertThat(RestAssuredMockMvc.given().get(PATH).statusCode()).isEqualTo(401);
        when(jwtDecoder.decode("invalid")).thenThrow(new BadJwtException("invalid"));
        assertThat(RestAssuredMockMvc.given().header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                .get(PATH).statusCode()).isEqualTo(401);
        verifyNoInteractions(queries, reviews);
    }

    @Test
    @DisplayName("일반 회원이나 삭제된 관리자는 목록과 종결에 접근하지 못한다")
    void rejectsNonAdministratorAndDeletedAdministrator() {
        when(admin.isAdmin()).thenReturn(false);
        assertThat(adminRequest().get(PATH).statusCode()).isEqualTo(403);
        assertThat(adminRequest().body("{\"status\":\"RESOLVED\",\"reason\":\"처리\"}")
                .put(PATH + "/" + REPORT + "/review").statusCode()).isEqualTo(403);
        when(admin.isAdmin()).thenReturn(true);
        when(admin.isDeleted()).thenReturn(true);
        assertThat(adminRequest().get(PATH).statusCode()).isEqualTo(403);
        verifyNoInteractions(queries, reviews);
    }

    @Test
    @DisplayName("없는 신고와 다른 종결 요청은 고정된 오류로 반환한다")
    void mapsNotFoundAndReviewConflict() {
        when(queries.findDetail(REPORT)).thenThrow(new AdminReportNotFoundException());
        var missing = adminRequest().get(PATH + "/" + REPORT);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.jsonPath().getString("code")).isEqualTo("ADMIN_REPORT_NOT_FOUND");
        when(reviews.review(eq(ADMIN), eq(REPORT), any())).thenThrow(new AdminReportReviewConflictException());
        var conflict = adminRequest().body("{\"status\":\"DISMISSED\",\"reason\":\"처리\"}")
                .put(PATH + "/" + REPORT + "/review");
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.jsonPath().getString("code")).isEqualTo("ADMIN_REPORT_REVIEW_CONFLICT");
    }

    private static Stream<String> invalidReviewBodies() {
        return Stream.of("{}", "{\"status\":\"RECEIVED\",\"reason\":\"비밀\"}",
                "{\"status\":\"UNKNOWN\",\"reason\":\"비밀\"}",
                "{\"status\":\"RESOLVED\",\"reason\":\"  \"}",
                "{\"status\":\"RESOLVED\",\"reason\":\"" + "😀".repeat(501) + "\"}",
                "{\"status\":\"RESOLVED\",\"reason\":\"비밀\\u0000\"}",
                "{\"status\":\"RESOLVED\",\"reason\":\"비밀\\ud800\"}");
    }

    private static AdminReportSnapshot snapshot(AdminReportStatus status) {
        return new AdminReportSnapshot(REPORT, null, ReportTargetType.COMMENT, TARGET, AUTHOR,
                ReportReason.HARASSMENT, "신고 상세", status, NOW.minusSeconds(100),
                status == AdminReportStatus.RECEIVED ? null : ADMIN,
                status == AdminReportStatus.RECEIVED ? null : NOW,
                status == AdminReportStatus.RECEIVED ? null : "처리 완료");
    }

    private static MockMvcRequestSpecification adminRequest() {
        return RestAssuredMockMvc.given().header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON_VALUE);
    }
}
