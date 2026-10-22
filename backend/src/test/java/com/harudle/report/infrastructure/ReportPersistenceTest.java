package com.harudle.report.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.HarudleApplication;
import com.harudle.common.testdata.DevTestDataVisibility;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportTargetType;
import com.harudle.report.repository.ReportRepository;
import com.harudle.report.repository.ReportSnapshot;
import com.harudle.report.repository.ReportTargetReader;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = HarudleApplication.class, properties = {
        "app.auth.access-token.secret-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.security.oauth2.client.registration.kakao.client-id=report-test-client",
        "spring.security.oauth2.client.registration.kakao.client-secret=report-test-secret",
        "harudle.generation.adapters.enabled=false",
        "harudle.generation.prompt-bootstrap.enabled=false",
        "harudle.generation.storage.r2.enabled=false",
        "harudle.push.fcm.enabled=false"
})
@AutoConfigureMockMvc
@EnabledIf("databaseAvailable")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReportPersistenceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-22T03:00:00Z");

    private static PostgreSQLContainer postgres;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean(enforceOverride = true)
    private DiaryGenerationWorker generationWorker;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReportRepository reports;

    @Autowired
    private ReportTargetReader targets;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private final List<UUID> fixtureUsers = new ArrayList<>();
    private final List<UUID> fixtureReports = new ArrayList<>();
    private UUID reporter;
    private UUID author;
    private UUID commenter;
    private UUID diary;
    private UUID feed;
    private UUID comment;

    static boolean databaseAvailable() {
        return System.getenv("HARUDLE_REPORT_TEST_DATABASE_URL") != null
                || DockerClientFactory.instance().isDockerAvailable();
    }

    @DynamicPropertySource
    static synchronized void databaseProperties(DynamicPropertyRegistry properties) {
        String localUrl = System.getenv("HARUDLE_REPORT_TEST_DATABASE_URL");
        if (localUrl != null) {
            requireDedicatedLocalDatabase(localUrl);
            configureDatabase(properties, localUrl,
                    environmentOrDefault("HARUDLE_REPORT_TEST_DATABASE_USERNAME", "harudle_report_test"),
                    environmentOrDefault("HARUDLE_REPORT_TEST_DATABASE_PASSWORD", ""));
            return;
        }
        if (postgres == null) {
            postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
            postgres.start();
        }
        configureDatabase(properties, postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void configureDatabase(
            DynamicPropertyRegistry properties, String url, String username, String password
    ) {
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.flyway.url", () -> url);
        properties.add("spring.flyway.user", () -> username);
        properties.add("spring.flyway.password", () -> password);
    }

    private static void requireDedicatedLocalDatabase(String url) {
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/harudle_report_test")) {
            throw new IllegalStateException("신고 테스트 전용 로컬 DB 주소가 필요합니다.");
        }
        int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
        if (port > 65_535) {
            throw new IllegalStateException("신고 테스트 전용 로컬 DB 주소가 필요합니다.");
        }
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }

    @BeforeEach
    void setUp() {
        reporter = member(null);
        author = member(null);
        commenter = member(null);
        diary = diary(author);
        feed = feed(diary, UUID.randomUUID());
        comment = comment(feed, commenter);
    }

    @AfterEach
    void tearDown() {
        fixtureReports.forEach(id -> jdbc.update("DELETE FROM reports WHERE id = ?", id));
        fixtureUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    @ParameterizedTest
    @EnumSource(ReportTargetType.class)
    @DisplayName("세 종류 신고는 서버가 조회한 대상 작성자와 선택적 빈 상세를 실제 DB에 보존한다")
    void persistEachTargetWithItsServerResolvedAuthor(ReportTargetType type) {
        UUID targetId = targetId(type);
        UUID targetAuthor = type == ReportTargetType.COMMENT ? commenter : author;
        assertThat(targets.findVisibleTargetAuthorId(reporter, type, targetId)).contains(targetAuthor);
        ReportSnapshot report = report(reporter, type, targetId, targetAuthor, ReportReason.OTHER, "");

        assertThat(insert(report)).isTrue();

        assertThat(reports.findReceiptIdByReporterAndTarget(reporter, type, targetId)).contains(report.id());
        assertStoredSubmission(report);
        assertThat(jdbc.queryForObject("SELECT status FROM reports WHERE id = ?", String.class, report.id()))
                .isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT comment_count FROM feeds WHERE id = ?", Integer.class, feed))
                .isZero();
    }

    @Test
    @DisplayName("실제 HTTP 신고는 보안과 DB를 거쳐 최초 사유를 보존하고 대상 삭제 후에도 같은 접수를 반환한다")
    void submitAndReplayThroughHttpSecurityServiceAndDatabase() throws Exception {
        UUID forgedReporter = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID forgedTargetUser = UUID.fromString("20000000-0000-4000-8000-000000000002");
        Instant issuedAt = Instant.now().minusSeconds(60);
        when(jwtDecoder.decode("report-persistence-token")).thenReturn(Jwt.withTokenValue("report-persistence-token")
                .header("alg", "HS256").subject(reporter.toString()).issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(1_800)).build());

        var first = mockMvc.perform(reportRequest(ReportReason.SPAM, "최초 접수 설명",
                forgedReporter, forgedTargetUser));
        List<UUID> storedIds = jdbc.queryForList("""
                SELECT id FROM reports WHERE reporter_id = ? AND target_type = 'FEED' AND target_id = ?
                """, UUID.class, reporter, feed);
        fixtureReports.addAll(storedIds);
        first.andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.reporterId").doesNotExist())
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist());
        assertThat(storedIds).hasSize(1);
        UUID savedId = storedIds.getFirst();
        first.andExpect(jsonPath("$.id").value(savedId.toString()));

        mockMvc.perform(reportRequest(ReportReason.HARASSMENT, "바꾸려는 재요청 설명",
                        forgedReporter, forgedTargetUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(savedId.toString()))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.duplicate").value(true));
        jdbc.update("DELETE FROM feeds WHERE id = ?", feed);
        mockMvc.perform(reportRequest(ReportReason.OTHER, "삭제 후 재요청 설명",
                        forgedReporter, forgedTargetUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(savedId.toString()))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.duplicate").value(true));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reports WHERE reporter_id = ? AND target_type = 'FEED' AND target_id = ?
                """, Long.class, reporter, feed)).isEqualTo(1);
        assertThat(reports.findReceiptIdByReporterAndTarget(reporter, ReportTargetType.FEED, feed))
                .contains(savedId);
        assertThat(jdbc.queryForMap("""
                SELECT reporter_id, target_user_id, reason, description FROM reports WHERE id = ?
                """, savedId))
                .containsEntry("reporter_id", reporter)
                .containsEntry("target_user_id", author)
                .containsEntry("reason", "SPAM")
                .containsEntry("description", "최초 접수 설명");
        assertThat(reporter).isNotEqualTo(forgedReporter);
        assertThat(author).isNotEqualTo(forgedTargetUser);
    }

    @Test
    @DisplayName("신고 저장은 호출자의 트랜잭션을 요구한다")
    void requireCallerTransactionForInsert() {
        ReportSnapshot report = report(reporter, ReportTargetType.FEED, feed, author, ReportReason.SPAM, "");

        assertThatThrownBy(() -> reports.insert(report)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(reportCount()).isZero();
    }

    @Test
    @DisplayName("중복 신고는 최초 사유와 상세를 유지하고 같은 트랜잭션의 후속 변경도 커밋한다")
    void keepOriginalReportWithoutPoisoningTheTransactionOnConflict() {
        ReportSnapshot initial = report(reporter, ReportTargetType.FEED, feed, author, ReportReason.SPAM, "최초 내용");
        ReportSnapshot duplicate = report(reporter, ReportTargetType.FEED, feed, author,
                ReportReason.HARASSMENT, "덮어쓰면 안 되는 내용");

        transactions.executeWithoutResult(status -> {
            assertThat(reports.insert(initial)).isTrue();
            assertThat(reports.insert(duplicate)).isFalse();
            jdbc.update("UPDATE users SET name = '후속 변경' WHERE id = ?", reporter);
        });

        assertThat(reports.findReceiptIdByReporterAndTarget(reporter, ReportTargetType.FEED, feed))
                .contains(initial.id());
        assertStoredSubmission(initial);
        assertThat(reportCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, reporter))
                .isEqualTo("후속 변경");
    }

    @Test
    @DisplayName("동일 대상에 대한 여덟 개 동시 신고는 한 행만 저장한다")
    void createOneReportUnderConcurrentDuplicateSubmissions() throws Exception {
        List<ReportSnapshot> attempts = IntStream.range(0, 8)
                .mapToObj(index -> report(reporter, ReportTargetType.FEED, feed, author,
                        ReportReason.SPAM, "동시 요청 " + index)).toList();
        CountDownLatch ready = new CountDownLatch(attempts.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = attempts.stream().<Callable<Boolean>>map(attempt -> () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("동시 신고 시작 신호를 기다리지 못했습니다.");
            }
            TransactionTemplate bounded = new TransactionTemplate(
                    Objects.requireNonNull(transactions.getTransactionManager()));
            bounded.setTimeout(10);
            return Boolean.TRUE.equals(bounded.execute(status -> reports.insert(attempt)));
        }).toList();
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Boolean>> results = tasks.stream().map(executor::submit).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Boolean> inserted = new ArrayList<>();
            for (Future<Boolean> result : results) {
                inserted.add(result.get(15, TimeUnit.SECONDS));
            }
            assertThat(inserted).filteredOn(Boolean::booleanValue).hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(reportCount()).isEqualTo(1);
        UUID winningId = reports.findReceiptIdByReporterAndTarget(reporter, ReportTargetType.FEED, feed)
                .orElseThrow();
        ReportSnapshot winner = attempts.stream().filter(attempt -> attempt.id().equals(winningId))
                .findFirst().orElseThrow();
        assertStoredSubmission(winner);
    }

    @Test
    @DisplayName("다른 신고자와 다른 대상 유형은 같은 UUID여도 독립적으로 신고할 수 있다")
    void scopeDuplicateIdentityToReporterTargetTypeAndTargetId() {
        UUID otherReporter = member(null);
        UUID overlappingFeedId = feed(diary(author), author);
        assertThat(insert(report(reporter, ReportTargetType.USER, author, author, ReportReason.SPAM, ""))).isTrue();
        assertThat(insert(report(otherReporter, ReportTargetType.USER, author, author, ReportReason.SPAM, "")))
                .isTrue();
        assertThat(insert(report(reporter, ReportTargetType.FEED, overlappingFeedId, author,
                ReportReason.SPAM, ""))).isTrue();

        assertThat(reportCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("신고 후 업무가 실패하면 신고도 롤백되고 같은 대상으로 재시도할 수 있다")
    void rollBackReportWithTheCallingTransaction() {
        ReportSnapshot report = report(reporter, ReportTargetType.FEED, feed, author, ReportReason.SPAM, "");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            reports.insert(report);
            throw new IllegalStateException("의도적인 후속 업무 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(reports.findReceiptIdByReporterAndTarget(reporter, ReportTargetType.FEED, feed)).isEmpty();
        assertThat(insert(report)).isTrue();
    }

    @Test
    @DisplayName("대상 콘텐츠와 작성자의 물리 삭제는 신고를 보존하고 신고자 삭제는 연결만 해제한다")
    void retainReportsAfterTargetDeletionAndDetachDeletedReporter() {
        ReportSnapshot feedReport = report(reporter, ReportTargetType.FEED, feed, author, ReportReason.SPAM, "피드");
        ReportSnapshot commentReport = report(reporter, ReportTargetType.COMMENT, comment, commenter,
                ReportReason.HARASSMENT, "댓글");
        ReportSnapshot userReport = report(reporter, ReportTargetType.USER, author, author, ReportReason.OTHER, "");
        List.of(feedReport, commentReport, userReport).forEach(value -> assertThat(insert(value)).isTrue());

        jdbc.update("DELETE FROM users WHERE id = ?", commenter);
        jdbc.update("DELETE FROM users WHERE id = ?", author);
        assertThat(reportCount()).isEqualTo(3);
        assertThat(reports.findReceiptIdByReporterAndTarget(reporter, ReportTargetType.COMMENT, comment))
                .contains(commentReport.id());
        assertStoredSubmission(commentReport);
        jdbc.update("DELETE FROM users WHERE id = ?", reporter);

        assertThat(reportCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reports WHERE reporter_id IS NULL", Long.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT target_user_id FROM reports WHERE id = ?", UUID.class,
                feedReport.id())).isEqualTo(author);
        assertThat(reports.findReceiptIdByReporterAndTarget(reporter, ReportTargetType.FEED, feed)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ReportTargetType.class, names = {"FEED", "COMMENT"})
    @DisplayName("공개 대상 검증 직후 콘텐츠가 삭제되어도 검증한 작성자 식별자를 신고에 보존한다")
    void retainSubmissionSnapshotWhenContentDisappearsAfterValidation(ReportTargetType type) {
        UUID targetId = targetId(type);
        UUID targetAuthor = targets.findVisibleTargetAuthorId(reporter, type, targetId).orElseThrow();
        ReportSnapshot report = report(reporter, type, targetId, targetAuthor, ReportReason.SPAM, "");
        jdbc.update("DELETE FROM feeds WHERE id = ?", feed);

        assertThat(targets.findVisibleTargetAuthorId(reporter, type, targetId)).isEmpty();
        assertThat(insert(report)).isTrue();
        assertThat(reports.findReceiptIdByReporterAndTarget(reporter, type, targetId)).contains(report.id());
        assertStoredSubmission(report);
    }

    @Test
    @DisplayName("OAuth가 없는 게스트와 탈퇴 회원은 회원 또는 USER 신고 대상으로 인정하지 않는다")
    void requireAnActiveOAuthMemberForReporterAndUserTarget() {
        UUID guest = user(null);
        UUID deletedMember = member(null);
        jdbc.update("UPDATE users SET deleted_at = ? WHERE id = ?", Timestamp.from(CREATED_AT), deletedMember);

        assertThat(reports.isActiveMember(reporter)).isTrue();
        for (UUID inactive : List.of(guest, deletedMember, UUID.randomUUID())) {
            assertThat(reports.isActiveMember(inactive)).isFalse();
            assertThat(targets.findVisibleTargetAuthorId(reporter, ReportTargetType.USER, inactive)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"comment", "feed", "diary", "feed_author"})
    @DisplayName("삭제된 댓글 또는 비공개가 된 부모 경로의 댓글은 신고 대상으로 조회하지 않는다")
    void excludeDeletedCommentsAndTheirInvisibleParents(String deleted) {
        switch (deleted) {
            case "comment" -> jdbc.update("UPDATE comments SET deleted_at = ? WHERE id = ?",
                    Timestamp.from(CREATED_AT), comment);
            case "feed" -> jdbc.update("UPDATE feeds SET deleted_at = ? WHERE id = ?",
                    Timestamp.from(CREATED_AT), feed);
            case "diary" -> jdbc.update("UPDATE diaries SET deleted_at = ? WHERE id = ?",
                    Timestamp.from(CREATED_AT), diary);
            case "feed_author" -> jdbc.update("UPDATE users SET deleted_at = ? WHERE id = ?",
                    Timestamp.from(CREATED_AT), author);
            default -> throw new AssertionError("정의되지 않은 삭제 대상");
        }

        assertThat(targets.findVisibleTargetAuthorId(reporter, ReportTargetType.COMMENT, comment)).isEmpty();
        if (!"comment".equals(deleted)) {
            assertThat(targets.findVisibleTargetAuthorId(reporter, ReportTargetType.FEED, feed)).isEmpty();
        }
    }

    @Test
    @DisplayName("탈퇴한 댓글 작성자의 공개 댓글은 콘텐츠 신고 대상으로 유지한다")
    void allowReportingContentWhoseCommentAuthorHasWithdrawn() {
        jdbc.update("UPDATE users SET deleted_at = ? WHERE id = ?", Timestamp.from(CREATED_AT), commenter);

        assertThat(targets.findVisibleTargetAuthorId(reporter, ReportTargetType.COMMENT, comment)).contains(commenter);
        assertThat(targets.findVisibleTargetAuthorId(reporter, ReportTargetType.USER, commenter)).isEmpty();
        assertThat(insert(report(reporter, ReportTargetType.COMMENT, comment, commenter, ReportReason.SPAM, "")))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod", ""})
    @DisplayName("dev 신고 쓰기는 테스트 계정 군을 분리하고 다른 환경은 테스트 이메일로 숨기지 않는다")
    void enforceDevTestDataPartitionsForAllTargetKinds(String environment) {
        UUID testActor = member("actor.loadtest.invalid");
        UUID testAuthor = member("author.loadtest.invalid");
        UUID testFeed = feed(diary(testAuthor), UUID.randomUUID());
        UUID testComment = comment(testFeed, testAuthor);
        var reader = new JpaReportTargetReader(entityManager, new DevTestDataVisibility(environment));
        for (ReportTargetType type : ReportTargetType.values()) {
            UUID testTarget = switch (type) {
                case USER -> testAuthor;
                case FEED -> testFeed;
                case COMMENT -> testComment;
            };
            assertThat(reader.findVisibleTargetAuthorId(testActor, type, testTarget)).contains(testAuthor);
            assertThat(reader.findVisibleTargetAuthorId(reporter, type, targetId(type))).isPresent();
            assertThat(reader.findVisibleTargetAuthorId(reporter, type, testTarget).isPresent())
                    .isEqualTo(!"dev".equals(environment));
            assertThat(reader.findVisibleTargetAuthorId(testActor, type, targetId(type)).isPresent())
                    .isEqualTo(!"dev".equals(environment));
        }
        UUID mixedOnNormalFeed = comment(feed, testAuthor);
        UUID mixedOnTestFeed = comment(testFeed, commenter);
        assertThat(reader.findVisibleTargetAuthorId(reporter, ReportTargetType.COMMENT, mixedOnNormalFeed).isPresent())
                .isEqualTo(!"dev".equals(environment));
        assertThat(reader.findVisibleTargetAuthorId(testActor, ReportTargetType.COMMENT, mixedOnTestFeed).isPresent())
                .isEqualTo(!"dev".equals(environment));
    }

    @Test
    @DisplayName("상세 500개 코드 포인트는 저장하고 501개는 DB 제약으로 거절하며 출력에는 상세를 숨긴다")
    void constrainDescriptionByCodePointsAndRedactSnapshotRendering() {
        String description = "😀".repeat(500);
        ReportSnapshot accepted = report(reporter, ReportTargetType.FEED, feed, author,
                ReportReason.OTHER, description);
        assertThat(insert(accepted)).isTrue();
        assertThat(reports.findReceiptIdByReporterAndTarget(reporter, ReportTargetType.FEED, feed))
                .contains(accepted.id());
        assertStoredSubmission(accepted);
        assertThat(accepted.toString()).contains("description=[REDACTED]").doesNotContain(description);
        ReportSnapshot tooLong = report(reporter, ReportTargetType.COMMENT, comment, commenter,
                ReportReason.OTHER, "😀".repeat(501));

        assertThatThrownBy(() -> insert(tooLong)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reportCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"target_type", "reason", "status", "description", "self"})
    @DisplayName("DB는 지원하지 않는 유형·사유·상태, null 상세와 자기 신고를 거절한다")
    void rejectInvalidDirectDatabaseWrites(String invalid) {
        String type = "target_type".equals(invalid) ? "DIARY" : "USER";
        String reason = "reason".equals(invalid) ? "UNKNOWN" : "SPAM";
        String state = "status".equals(invalid) ? "UNKNOWN" : "RECEIVED";
        String description = "description".equals(invalid) ? null : "";
        UUID targetAuthor = "self".equals(invalid) ? reporter : author;

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO reports (id, reporter_id, target_type, target_id, target_user_id,
                                     reason, description, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), reporter, type, author, targetAuthor, reason, description, state))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(reportCount()).isZero();
    }

    @Test
    @DisplayName("신고 스키마는 콘텐츠 원문 사본 없이 대상 식별자만 보존한다")
    void createOnlyTheSubmissionMetadataColumnsThroughFlyway() {
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'reports' ORDER BY ordinal_position
                """, String.class)).containsExactly("id", "reporter_id", "target_type", "target_id", "target_user_id",
                "reason", "description", "status", "created_at");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history WHERE version = '30' AND success
                """, Long.class)).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder reportRequest(
            ReportReason reason, String description, UUID forgedReporter, UUID forgedTargetUser
    ) {
        String csrf = "report-persistence-cookie-and-header";
        return post("/api/v1/reports")
                .header(HttpHeaders.AUTHORIZATION, "Bearer report-persistence-token")
                .cookie(new Cookie("XSRF-TOKEN", csrf))
                .header("X-XSRF-TOKEN", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"targetType":"FEED","targetId":"%s","reason":"%s","description":"%s",
                         "reporterId":"%s","targetUserId":"%s"}
                        """.formatted(feed, reason.name(), description, forgedReporter, forgedTargetUser));
    }

    private UUID targetId(ReportTargetType type) {
        return switch (type) {
            case USER -> author;
            case FEED -> feed;
            case COMMENT -> comment;
        };
    }

    private ReportSnapshot report(UUID actor, ReportTargetType type, UUID target, UUID targetAuthor,
            ReportReason reason, String description) {
        UUID id = UUID.randomUUID();
        fixtureReports.add(id);
        return new ReportSnapshot(id, actor, type, target, targetAuthor, reason, description, CREATED_AT);
    }

    private void assertStoredSubmission(ReportSnapshot expected) {
        assertThat(jdbc.queryForMap("""
                SELECT reporter_id, target_type, target_id, target_user_id, reason, description
                FROM reports WHERE id = ?
                """, expected.id()))
                .containsEntry("reporter_id", expected.reporterId())
                .containsEntry("target_type", expected.targetType().name())
                .containsEntry("target_id", expected.targetId())
                .containsEntry("target_user_id", expected.targetUserId())
                .containsEntry("reason", expected.reason().name())
                .containsEntry("description", expected.description());
        assertThat(jdbc.queryForObject("SELECT created_at FROM reports WHERE id = ?", Timestamp.class, expected.id()))
                .isEqualTo(Timestamp.from(expected.createdAt()));
    }

    private boolean insert(ReportSnapshot report) {
        return Boolean.TRUE.equals(transactions.execute(status -> reports.insert(report)));
    }

    private long reportCount() {
        if (fixtureReports.isEmpty()) {
            return 0;
        }
        return jdbc.queryForObject("SELECT count(*) FROM reports WHERE id IN ("
                + String.join(",", fixtureReports.stream().map(id -> "?").toList()) + ")",
                Long.class, fixtureReports.toArray());
    }

    private UUID user(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, primary_email, name, nickname, profile_image_code)
                VALUES (?, ?, '신고통합회원', ?, 1)
                """, id, email, "신고" + id.toString().substring(0, 8));
        fixtureUsers.add(id);
        return id;
    }

    private UUID member(String email) {
        UUID id = user(email);
        jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'KAKAO', ?)",
                id, id.toString());
        return id;
    }

    private UUID diary(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, DATE '2026-10-22', ?)",
                id, owner, "신고 대상 일기");
        return id;
    }

    private UUID feed(UUID sourceDiary, UUID id) {
        jdbc.update("""
                INSERT INTO feeds (id, diary_id, category_id)
                VALUES (?, ?, (SELECT id FROM categories WHERE name = '일상'))
                """, id, sourceDiary);
        return id;
    }

    private UUID comment(UUID parentFeed, UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO comments (id, feed_id, author_id, content) VALUES (?, ?, ?, ?)",
                id, parentFeed, owner, "신고 대상 댓글");
        return id;
    }
}
