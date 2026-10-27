package com.harudle.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.admin.query.AdminReportSnapshot;
import com.harudle.admin.query.AdminReportStatus;
import com.harudle.admin.service.AdminReportQueryService;
import com.harudle.admin.service.AdminReportReview;
import com.harudle.admin.service.AdminReportReviewService;
import com.harudle.admin.service.exception.AdminReportReviewConflictException;
import com.harudle.moderation.domain.ModerationChange;
import com.harudle.moderation.domain.ModerationState;
import com.harudle.moderation.domain.ModerationTargetType;
import com.harudle.moderation.repository.ModerationRepository;
import com.harudle.moderation.service.AdminModerationService;
import com.harudle.moderation.service.ModerationConflictException;
import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportSubmission;
import com.harudle.report.domain.ReportTargetType;
import com.harudle.report.service.ReportService;
import com.harudle.support.AdminModerationDatabaseTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class AdminReportPersistenceTest extends AdminModerationDatabaseTestSupport {

    private static final Instant CREATED_AT = Instant.parse("2026-10-23T03:00:00Z");
    private static final String PRIVATE_DIARY = "심사 API에 공개하지 않을 비공개 원문";

    @Autowired private AdminReportRepository reports;
    @Autowired private AdminReportQueryService queries;
    @Autowired private AdminReportReviewService reviews;
    @Autowired private ReportService submissions;
    @Autowired private AdminModerationService moderation;
    @Autowired private ModerationRepository moderationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    private final List<UUID> fixtureUsers = new ArrayList<>();
    private final List<UUID> fixtureReports = new ArrayList<>();
    private final List<UUID> fixtureModerationTargets = new ArrayList<>();
    private UUID admin;
    private UUID secondAdmin;
    private UUID reporter;
    private UUID author;
    private UUID commenter;
    private UUID diary;
    private UUID feed;
    private UUID comment;
    private Long prompt;

    @BeforeEach
    void setUp() {
        admin = user(true);
        secondAdmin = user(true);
        reporter = user(false);
        author = user(false);
        commenter = user(false);
        prompt = jdbc.queryForObject("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('심사 테스트', '심사 테스트', ?) RETURNING id
                """, Long.class, "references/moderation-" + UUID.randomUUID());
        diary = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries(id,user_id,diary_date,source_text) VALUES (?,?,DATE '2026-10-23',?)",
                diary, author, PRIVATE_DIARY);
        jdbc.update("""
                INSERT INTO diary_generations (id,diary_id,prompt_id,idempotency_key,request_fingerprint,
                    status,title,image_object_key,completed_at)
                VALUES (?,?,?,?,?,'SUCCEEDED','공개 제목',?,CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), diary, prompt, UUID.randomUUID(), "a".repeat(64),
                "generated/moderation-" + UUID.randomUUID() + ".webp");
        feed = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO feeds(id,diary_id,category_id)
                VALUES (?, ?, (SELECT id FROM categories WHERE name = '일상'))
                """, feed, diary);
        comment = UUID.randomUUID();
        jdbc.update("INSERT INTO comments(id,feed_id,author_id,content) VALUES (?,?,?,?)",
                comment, feed, commenter, "신고된 댓글 본문");
    }

    @AfterEach
    void cleanUp() {
        fixtureReports.forEach(id -> jdbc.update("DELETE FROM reports WHERE id = ?", id));
        List<UUID> targets = new ArrayList<>(fixtureUsers);
        targets.add(feed);
        targets.add(comment);
        targets.addAll(fixtureModerationTargets);
        for (UUID id : targets) {
            jdbc.update("DELETE FROM moderation_events WHERE target_id = ?", id);
            jdbc.update("DELETE FROM moderation_states WHERE target_id = ?", id);
        }
        fixtureUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
        if (prompt != null) {
            jdbc.update("DELETE FROM generation_prompts WHERE id = ?", prompt);
        }
    }

    @Test
    @DisplayName("실제 상태별 목록은 같은 생성 시각에도 UUID 내림차순과 정확한 전체 수를 반환한다")
    void paginatesReportsByStatusAndStableOrder() {
        long prefix = UUID.randomUUID().getMostSignificantBits();
        UUID first = report(new UUID(prefix, 1), ReportTargetType.FEED, feed, author);
        UUID second = report(new UUID(prefix, 2), ReportTargetType.COMMENT, comment, commenter);
        UUID dismissed = report(UUID.randomUUID(), ReportTargetType.USER, author, author);
        reviews.review(admin, dismissed, new AdminReportReview(AdminReportStatus.DISMISSED, "위반 아님"));

        var page = queries.search(AdminReportStatus.RECEIVED, 0, 1);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(item -> item.id()).containsExactly(second);
        assertThat(queries.search(AdminReportStatus.RECEIVED, 1, 1).content())
                .extracting(item -> item.id()).containsExactly(first);
        assertThat(queries.search(AdminReportStatus.DISMISSED, 0, 20).content())
                .extracting(item -> item.id()).containsExactly(dismissed);
        assertThat(queries.search(AdminReportStatus.RESOLVED, 0, 20).content()).isEmpty();
    }

    @Test
    @DisplayName("신고자와 처리자 물리 삭제 후에도 종결 상태와 사유 및 시각을 읽을 수 있다")
    void retainsReviewAfterReporterAndReviewerDeletion() {
        UUID id = report(UUID.randomUUID(), ReportTargetType.FEED, feed, author);
        var completed = reviews.review(admin, id, new AdminReportReview(AdminReportStatus.RESOLVED, "조치 완료"));
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", reporter, admin);

        var actual = queries.findDetail(id).report();
        assertThat(actual.reporterId()).isNull();
        assertThat(actual.reviewedBy()).isNull();
        assertThat(actual.reviewedAt()).isEqualTo(completed.reviewedAt());
        assertThat(actual.reviewReason()).isEqualTo("조치 완료");
        assertThat(reviews.review(secondAdmin, id,
                new AdminReportReview(AdminReportStatus.RESOLVED, "조치 완료"))).isEqualTo(actual);
    }

    @Test
    @DisplayName("종결 후 일반 회원의 같은 신고 재접수는 최초 접수와 사유를 유지한다")
    void preservesExistingSubmissionContractAfterReview() {
        var submission = new ReportSubmission(ReportTargetType.COMMENT, comment,
                ReportReason.HARASSMENT, "최초 상세");
        var receipt = submissions.submit(reporter, submission);
        fixtureReports.add(receipt.id());
        reviews.review(admin, receipt.id(), new AdminReportReview(AdminReportStatus.RESOLVED, "조치 완료"));
        var replay = submissions.submit(reporter,
                new ReportSubmission(ReportTargetType.COMMENT, comment, ReportReason.OTHER, "바꾸려는 상세"));
        assertThat(replay.id()).isEqualTo(receipt.id());
        assertThat(replay.duplicate()).isTrue();
        assertThat(queries.findDetail(receipt.id()).report().description()).isEqualTo("최초 상세");
    }

    @Test
    @DisplayName("논리 삭제된 댓글 원문은 확인하고 물리 삭제 뒤에는 신고와 원본 없음 표시만 남긴다")
    void readsSoftDeletedEvidenceAndMissingPhysicalTarget() {
        UUID id = report(UUID.randomUUID(), ReportTargetType.COMMENT, comment, commenter);
        jdbc.update("UPDATE comments SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", comment);
        jdbc.update("UPDATE feeds SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", feed);
        var detail = queries.findDetail(id);
        assertThat(detail.target().exists()).isTrue();
        assertThat(detail.target().deleted()).isTrue();
        assertThat(detail.target().parentDeleted()).isTrue();
        assertThat(detail.target().publiclyVisible()).isFalse();
        assertThat(detail.target().commentContent()).isEqualTo("신고된 댓글 본문");
        assertThat(detail.target().title()).isEqualTo("공개 제목");
        assertThat(detail.toString()).doesNotContain(PRIVATE_DIARY, "신고된 댓글 본문");
        jdbc.update("DELETE FROM comments WHERE id=?", comment);
        assertThat(queries.findDetail(id).target().exists()).isFalse();
        assertThat(queries.findDetail(id).report().targetUserId()).isEqualTo(commenter);
    }

    @Test
    @DisplayName("관리자 숨김은 댓글과 부모 피드에 각각 반영되고 해제하면 원본 공개성이 돌아온다")
    void reflectsCommentAndParentFeedModerationWithoutDeletingEvidence() {
        UUID id = report(UUID.randomUUID(), ReportTargetType.COMMENT, comment, commenter);
        assertThat(queries.findDetail(id).target().publiclyVisible()).isTrue();
        restrict("FEED", feed);
        var parentHidden = queries.findDetail(id).target();
        assertThat(parentHidden.parentHidden()).isTrue();
        assertThat(parentHidden.hidden()).isFalse();
        assertThat(parentHidden.publiclyVisible()).isFalse();
        jdbc.update("DELETE FROM moderation_states WHERE target_type='FEED' AND target_id=?", feed);
        restrict("COMMENT", comment);
        var ownHidden = queries.findDetail(id).target();
        assertThat(ownHidden.hidden()).isTrue();
        assertThat(ownHidden.commentContent()).isEqualTo("신고된 댓글 본문");
        jdbc.update("UPDATE moderation_states SET restricted=false WHERE target_type='COMMENT' AND target_id=?", comment);
        assertThat(queries.findDetail(id).target().publiclyVisible()).isTrue();
        jdbc.update("UPDATE users SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", commenter);
        assertThat(queries.findDetail(id).target().authorDeleted()).isTrue();
        assertThat(queries.findDetail(id).target().publiclyVisible()).isTrue();
    }

    @Test
    @DisplayName("같은 일기를 새 피드로 재게시하고 새 피드 제한을 해제해도 이전 피드 숨김은 계속 적용된다")
    void preservesDiaryWideRestrictionAcrossFeedRepublication() {
        restrict("FEED", feed);
        jdbc.update("UPDATE feeds SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", feed);
        UUID republished = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO feeds(id,diary_id,category_id)
                VALUES (?, ?, (SELECT id FROM categories WHERE name='일상'))
                """, republished, diary);
        UUID id = report(UUID.randomUUID(), ReportTargetType.FEED, republished, author);
        var target = queries.findDetail(id).target();
        assertThat(target.hidden()).isFalse();
        assertThat(target.parentHidden()).isTrue();
        assertThat(target.publiclyVisible()).isFalse();
        restrict("FEED", republished);
        jdbc.update("UPDATE moderation_states SET restricted=false WHERE target_type='FEED' AND target_id=?",
                republished);
        assertThat(queries.findDetail(id).target().publiclyVisible()).isFalse();
        UUID newComment = UUID.randomUUID();
        jdbc.update("INSERT INTO comments(id,feed_id,author_id,content) VALUES (?,?,?,'재게시 댓글')",
                newComment, republished, commenter);
        UUID commentReport = report(UUID.randomUUID(), ReportTargetType.COMMENT, newComment, commenter);
        assertThat(queries.findDetail(commentReport).target().parentHidden()).isTrue();
        jdbc.update("UPDATE moderation_states SET restricted=false WHERE target_type='FEED' AND target_id=?", feed);
        assertThat(queries.findDetail(id).target().publiclyVisible()).isTrue();
        assertThat(queries.findDetail(commentReport).target().publiclyVisible()).isTrue();
    }

    @Test
    @DisplayName("사용자 로그인 제재는 공개 사용자 존재 여부와 독립이며 회원 논리 삭제는 구분한다")
    void distinguishesAccountRestrictionFromDeletedUserEvidence() {
        UUID id = report(UUID.randomUUID(), ReportTargetType.USER, author, author);
        restrict("USER", author);
        try {
            assertThat(queries.findDetail(id).target().publiclyVisible()).isTrue();
            jdbc.update("UPDATE users SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", author);
            assertThat(queries.findDetail(id).target().exists()).isTrue();
            assertThat(queries.findDetail(id).target().authorDeleted()).isTrue();
            assertThat(queries.findDetail(id).target().publiclyVisible()).isFalse();
        } finally {
            jdbc.update("DELETE FROM moderation_states WHERE target_type='USER' AND target_id=?", author);
        }
    }

    @Test
    @DisplayName("심사 감사 필드 저장 실패는 종결을 포함해 전체 트랜잭션을 취소한다")
    void rollsBackReviewWhenAuditWriteFails() {
        UUID id = report(UUID.randomUUID(), ReportTargetType.FEED, feed, author);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            reports.findByIdForUpdate(id).orElseThrow();
            reports.completeReview(id, admin, new AdminReportReview(AdminReportStatus.RESOLVED, "조치 완료"), CREATED_AT);
            jdbc.update("UPDATE reports SET review_reason=NULL WHERE id=?", id);
        })).isInstanceOf(RuntimeException.class);
        var after = queries.findDetail(id).report();
        assertThat(after.status()).isEqualTo(AdminReportStatus.RECEIVED);
        assertThat(after.reviewedBy()).isNull();
        assertThat(after.reviewedAt()).isNull();
        assertThat(after.reviewReason()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("두 실제 심사는 행 잠금으로 순서가 정해지고 같은 요청은 재사용하며 다른 요청은 충돌한다")
    void serializesConcurrentReviewsAndPreservesWinningAudit(boolean sameDecision) throws Exception {
        UUID id = report(UUID.randomUUID(), ReportTargetType.FEED, feed, author);
        var firstReview = new AdminReportReview(AdminReportStatus.RESOLVED, "첫 심사 사유");
        var secondReview = sameDecision ? firstReview
                : new AdminReportReview(AdminReportStatus.DISMISSED, "다른 심사 사유");
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        int[] secondPid = new int[1];
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AdminReportSnapshot> first = executor.submit(() -> transactions.execute(status -> {
                reports.findByIdForUpdate(id).orElseThrow();
                firstLocked.countDown();
                await(releaseFirst);
                return reviews.review(admin, id, firstReview);
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Object> second = executor.submit(() -> {
                try {
                    return transactions.execute(status -> {
                        secondPid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                        secondStarted.countDown();
                        return reviews.review(secondAdmin, id, secondReview);
                    });
                } catch (AdminReportReviewConflictException exception) {
                    return exception;
                }
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlockedByAnotherTransaction(secondPid[0]);
            releaseFirst.countDown();
            var winner = first.get(5, TimeUnit.SECONDS);
            Object loser = second.get(5, TimeUnit.SECONDS);
            if (sameDecision) {
                assertThat(loser).isEqualTo(winner);
            } else {
                assertThat(loser).isInstanceOf(AdminReportReviewConflictException.class);
            }
            var stored = queries.findDetail(id).report();
            assertThat(stored.reviewedBy()).isEqualTo(admin);
            assertThat(stored.reviewedAt()).isEqualTo(winner.reviewedAt());
            assertThat(stored.reviewReason()).isEqualTo("첫 심사 사유");
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("계정 정지와 세션 폐기는 같은 트랜잭션이며 해제 후에도 폐기한 토큰은 복원하지 않는다")
    void suspendsAccountWithAuditAndRevokesExistingSessions() {
        long token = refreshToken();
        var first = moderation.change(admin, ModerationTargetType.USER, author, new ModerationChange(true, 0, "정지 사유"));
        var stored = moderation.find(admin, ModerationTargetType.USER, author);
        assertThat(first).isEqualTo(stored);
        assertThat(stored.restricted()).isTrue();
        assertThat(stored.version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM refresh_tokens WHERE id=?",
                Boolean.class, token)).isTrue();
        assertThat(moderation.change(admin, ModerationTargetType.USER, author,
                new ModerationChange(true, 0, "  정지 사유  "))).isEqualTo(stored);
        assertThat(moderation.history(admin, ModerationTargetType.USER, author, 0, 20).totalElements()).isEqualTo(1);
        moderation.change(secondAdmin, ModerationTargetType.USER, author, new ModerationChange(false, 1, "해제 사유"));
        var restored = moderation.find(admin, ModerationTargetType.USER, author);
        assertThat(restored.restricted()).isFalse();
        assertThat(restored.version()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM refresh_tokens WHERE id=?",
                Boolean.class, token)).isTrue();
        assertThat(moderation.history(admin, ModerationTargetType.USER, author, 0, 1).content())
                .extracting(event -> event.version()).containsExactly(2L);
    }

    @Test
    @DisplayName("현 상태와 같은 조치는 이력을 만들지 않고 숨김 해제는 원본의 논리 삭제를 되돌리지 않는다")
    void noOpAndUnhideDoNotRestoreDeletedContent() {
        var noop = moderation.change(admin, ModerationTargetType.COMMENT, comment,
                new ModerationChange(false, 0, "이미 공개 상태"));
        assertThat(noop.version()).isZero();
        assertThat(moderation.history(admin, ModerationTargetType.COMMENT, comment, 0, 20).content()).isEmpty();
        UUID id = report(UUID.randomUUID(), ReportTargetType.COMMENT, comment, commenter);
        moderation.change(admin, ModerationTargetType.COMMENT, comment, new ModerationChange(true, 0, "댓글 숨김"));
        jdbc.update("UPDATE comments SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", comment);
        moderation.change(admin, ModerationTargetType.COMMENT, comment, new ModerationChange(false, 1, "숨김 해제"));
        var target = queries.findDetail(id).target();
        assertThat(target.hidden()).isFalse();
        assertThat(target.deleted()).isTrue();
        assertThat(target.publiclyVisible()).isFalse();
        assertThat(target.commentContent()).isEqualTo("신고된 댓글 본문");
    }

    @ParameterizedTest
    @ValueSource(strings = {"moderation_events", "refresh_tokens"})
    @DisplayName("감사 이력 또는 세션 폐기 SQL 실패 시 상태 변경과 이력 및 토큰 폐기를 모두 취소한다")
    void rollsBackModerationStateAuditAndTokensWhenDatabaseWriteFails(String table) {
        long token = refreshToken();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String function = "test_moderation_failure_" + suffix;
        String trigger = "test_moderation_failure";
        String targetColumn = table.equals("moderation_events") ? "target_id" : "user_id";
        String event = table.equals("moderation_events") ? "BEFORE INSERT" : "BEFORE UPDATE";
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW." + targetColumn + " = '" + author + "'::uuid THEN "
                + "RAISE EXCEPTION 'forced moderation persistence failure'; END IF; RETURN NEW; END $$");
        try {
            jdbc.execute("CREATE TRIGGER " + trigger + " " + event + " ON " + table
                    + " FOR EACH ROW EXECUTE FUNCTION " + function + "()");
            assertThatThrownBy(() -> moderation.change(admin, ModerationTargetType.USER, author,
                    new ModerationChange(true, 0, "실패할 정지 요청"))).isInstanceOf(RuntimeException.class);
            assertThat(moderationRepository.find(ModerationTargetType.USER, author)).isEmpty();
            assertThat(moderation.history(admin, ModerationTargetType.USER, author, 0, 20).content()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM refresh_tokens WHERE id=?",
                    Boolean.class, token)).isTrue();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + table);
            jdbc.execute("DROP FUNCTION " + function + "()");
        }
    }

    @Test
    @DisplayName("두 관리자의 상반된 계정 조치는 실제 사용자 행 잠금 후 새 버전을 검사해 오래된 요청을 거절한다")
    void serializesOppositeModerationChangesAndRejectsStaleVersion() throws Exception {
        CountDownLatch targetLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        int[] secondPid = new int[1];
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ModerationState> first = executor.submit(() -> transactions.execute(status -> {
                assertThat(moderationRepository.lockManageableTarget(ModerationTargetType.USER, author)).isTrue();
                targetLocked.countDown();
                await(releaseFirst);
                return moderation.change(admin, ModerationTargetType.USER, author,
                        new ModerationChange(true, 0, "먼저 확정할 정지"));
            }));
            assertThat(targetLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Object> second = executor.submit(() -> {
                try {
                    return transactions.execute(status -> {
                        secondPid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                        secondStarted.countDown();
                        return moderation.change(secondAdmin, ModerationTargetType.USER, author,
                                new ModerationChange(false, 0, "이전 화면의 해제 요청"));
                    });
                } catch (ModerationConflictException exception) {
                    return exception;
                }
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlockedByAnotherTransaction(secondPid[0]);
            releaseFirst.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).restricted()).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isInstanceOf(ModerationConflictException.class);
            assertThat(moderation.find(admin, ModerationTargetType.USER, author).version()).isEqualTo(1);
            assertThat(moderation.history(admin, ModerationTargetType.USER, author, 0, 20).totalElements()).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long refreshToken() {
        return jdbc.queryForObject("""
                INSERT INTO refresh_tokens(user_id,token_hash,expires_at)
                VALUES (?,?,CURRENT_TIMESTAMP + INTERVAL '1 day') RETURNING id
                """, Long.class, author, UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private UUID report(UUID id, ReportTargetType type, UUID targetId, UUID targetAuthor) {
        jdbc.update("""
                INSERT INTO reports(id,reporter_id,target_type,target_id,target_user_id,reason,description,created_at)
                VALUES (?,?,?,?,?,'SPAM','최초 신고 상세',?)
                """, id, reporter, type.name(), targetId, targetAuthor, Timestamp.from(CREATED_AT));
        fixtureReports.add(id);
        return id;
    }

    private UUID user(boolean administrator) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,name,nickname,profile_image_code,role) VALUES (?,'심사회원',?,1,?)",
                id, "심사" + id.toString().substring(0, 8), administrator ? "ADMIN" : "USER");
        jdbc.update("INSERT INTO oauth_accounts(user_id,provider,provider_subject) VALUES (?,'KAKAO',?)",
                id, id.toString());
        fixtureUsers.add(id);
        return id;
    }

    private void restrict(String type, UUID id) {
        fixtureModerationTargets.add(id);
        jdbc.update("""
                INSERT INTO moderation_states(target_type,target_id,restricted,version,reason,actor_id,changed_at)
                VALUES (?,?,true,1,'검증용 숨김',?,CURRENT_TIMESTAMP)
                """, type, id, admin);
    }

    private void assertBlockedByAnotherTransaction(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid);
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("두 번째 실제 트랜잭션이 첫 심사 행 잠금을 기다리지 않았습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("심사 경합 테스트 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("심사 경합 테스트 중단");
        }
    }
}
