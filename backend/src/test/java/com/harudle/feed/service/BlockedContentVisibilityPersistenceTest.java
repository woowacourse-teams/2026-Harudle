package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.comment.query.CommentCursor;
import com.harudle.comment.repository.CommentRepository;
import com.harudle.comment.service.CommentService;
import com.harudle.feed.query.FeedSort;
import com.harudle.feed.repository.FeedListRepository;
import com.harudle.feed.repository.FeedQueryRepository;
import com.harudle.feed.repository.FeedSnapshot;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.notification.repository.NotificationRepository;
import com.harudle.notification.repository.NotificationSnapshot;
import com.harudle.notification.service.NotificationService;
import com.harudle.notification.service.exception.NotificationNotFoundException;
import com.harudle.report.domain.InvalidReportException;
import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportSubmission;
import com.harudle.report.domain.ReportTargetType;
import com.harudle.report.service.ReportReceipt;
import com.harudle.report.service.ReportService;
import com.harudle.share.service.ShareLinkQueryService;
import com.harudle.share.service.exception.ShareNotFoundException;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.support.BlockDatabaseTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "harudle.generation.adapters.enabled=false",
        "harudle.profile.image-base-url=https://test.example.com/profiles",
        "KAKAO_REST_API_KEY=block-test-client",
        "KAKAO_CLIENT_SECRET=block-test-secret",
        "JWT_SECRET_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class BlockedContentVisibilityPersistenceTest extends BlockDatabaseTestSupport {

    private static final Instant TIME = Instant.parse("2026-10-22T01:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private FeedListRepository feedLists;
    @Autowired private FeedQueryRepository feedQueries;
    @Autowired private FeedQueryService feedService;
    @Autowired private CommentRepository comments;
    @Autowired private CommentService commentService;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationService notificationService;
    @Autowired private ShareLinkQueryService shares;
    @Autowired private ReportService reportService;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean(enforceOverride = true) private DiaryGenerationWorker generationWorker;

    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> fixtureReports = new ArrayList<>();
    private UUID viewer;
    private UUID blocked;
    private UUID other;
    private UUID viewerFeed;
    private UUID blockedFeed;
    private UUID otherFeed;
    private UUID blockedShare;
    private long categoryId;
    private long promptId;

    @BeforeEach
    void setUp() {
        viewer = member();
        blocked = member();
        other = member();
        categoryId = jdbc.queryForObject("INSERT INTO categories (name) VALUES (?) RETURNING id", Long.class,
                "차단조회" + UUID.randomUUID().toString().substring(0, 8));
        promptId = jdbc.queryForObject("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('테스트', '테스트', ?) RETURNING id
                """, Long.class, "references/block-visibility/" + UUID.randomUUID());
        viewerFeed = feed(viewer, 1);
        otherFeed = feed(other, 2);
        blockedFeed = feed(blocked, 3);
        blockedShare = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO share_links (id, generation_id)
                SELECT ?, g.id FROM diary_generations g JOIN feeds f ON f.diary_id = g.diary_id WHERE f.id = ?
                """, blockedShare, blockedFeed);
    }

    @AfterEach
    void tearDown() {
        fixtureReports.forEach(id -> jdbc.update("DELETE FROM reports WHERE id = ?", id));
        fixtureReports.clear();
        users.forEach(user -> jdbc.update("DELETE FROM users WHERE id = ?", user));
        users.clear();
        jdbc.update("DELETE FROM generation_prompts WHERE id = ?", promptId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
    }

    @ParameterizedTest
    @EnumSource(FeedSort.class)
    @DisplayName("차단한 상대의 피드는 정렬과 LIMIT 전에 숨기고 익명 공개 목록은 유지한다")
    void filtersFeedsBeforePagination(FeedSort sort) {
        block(viewer, blocked);

        assertThat(feedLists.findAfter(sort, categoryId, null, 1, viewer))
                .extracting(FeedSnapshot::id).containsExactly(otherFeed);
        assertThat(feedLists.findAfter(sort, categoryId, null, 10, blocked))
                .extracting(FeedSnapshot::id).containsExactly(blockedFeed, otherFeed);
        assertThat(feedLists.findAfter(sort, categoryId, null, 10, null))
                .extracting(FeedSnapshot::id).containsExactly(blockedFeed, otherFeed, viewerFeed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feeds WHERE category_id = ?", Long.class, categoryId))
                .isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("어느 방향으로 차단하더라도 두 피드 상세 쿼리와 서비스가 상대 피드를 숨긴다")
    void hidesFeedDetailsInBothDirections(boolean reverse) {
        block(reverse ? blocked : viewer, reverse ? viewer : blocked);

        assertThat(feedQueries.findActiveSnapshotById(blockedFeed, viewer)).isEmpty();
        assertThat(feedQueries.findVisibleSnapshotById(blockedFeed, viewer)).isEmpty();
        assertThat(feedQueries.findActiveSnapshotById(viewerFeed, blocked)).isEmpty();
        assertThatThrownBy(() -> feedService.getDetail(viewer, blockedFeed)).isInstanceOf(FeedNotFoundException.class);
        assertThat(feedQueries.findActiveSnapshotById(blockedFeed, null)).isPresent();
        assertThat(feedQueries.findVisibleSnapshotById(blockedFeed, null)).isPresent();
    }

    @Test
    @DisplayName("한쪽만 차단을 해제하면 숨김을 유지하고 양쪽 해제 후 원본 피드가 다시 보인다")
    void restoresFeedOnlyAfterBothDirectionsAreRemoved() {
        block(viewer, blocked);
        block(blocked, viewer);
        unblock(viewer, blocked);
        assertThat(feedQueries.findActiveSnapshotById(blockedFeed, viewer)).isEmpty();

        unblock(blocked, viewer);

        assertThat(feedService.getDetail(viewer, blockedFeed).id()).isEqualTo(blockedFeed);
        assertThat(feedQueries.findVisibleSnapshotById(blockedFeed, viewer)).isPresent();
    }

    @Test
    @DisplayName("중립 피드의 차단 상대 댓글은 페이지 경계 전에 숨기며 해제하면 같은 댓글이 복구된다")
    void filtersCommentAuthorsBeforePaginationWithoutDeletingHistory() {
        UUID hidden = comment(blocked, otherFeed, 1);
        UUID first = comment(other, otherFeed, 2);
        UUID second = comment(viewer, otherFeed, 3);
        block(blocked, viewer);

        var page = commentService.list(otherFeed, viewer, null, 1);
        assertThat(page.items()).extracting(item -> item.id()).containsExactly(first);
        assertThat(page.hasNext()).isTrue();
        assertThat(commentService.list(otherFeed, viewer, page.nextCursor(), 1).items())
                .extracting(item -> item.id()).containsExactly(second);
        assertThat(commentService.list(otherFeed, null, null, 1).items())
                .extracting(item -> item.id()).containsExactly(hidden);
        assertThat(comments.findAfter(otherFeed, Optional.of(new CommentCursor(otherFeed, TIME.plusSeconds(1), hidden)),
                1, viewer)).extracting(item -> item.id()).containsExactly(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments WHERE feed_id = ?", Long.class, otherFeed))
                .isEqualTo(3);

        unblock(blocked, viewer);

        assertThat(commentService.list(otherFeed, viewer, null, 1).items())
                .extracting(item -> item.id()).containsExactly(hidden);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("차단한 피드 작성자의 댓글 목록은 404이며 익명 조회와 저장된 댓글은 유지한다")
    void hidesCommentsWhenParentAuthorIsBlocked(boolean reverse) {
        UUID commentId = comment(other, blockedFeed, 1);
        block(reverse ? blocked : viewer, reverse ? viewer : blocked);

        assertThatThrownBy(() -> commentService.list(blockedFeed, viewer, null, 20))
                .isInstanceOf(FeedNotFoundException.class);
        assertThat(comments.findAfter(blockedFeed, Optional.empty(), 20, viewer)).isEmpty();
        assertThat(commentService.list(blockedFeed, null, null, 20).items())
                .extracting(item -> item.id()).containsExactly(commentId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("차단 상대가 알림 행동자이면 목록·미읽음·단건·읽음을 모두 숨기고 원본 이력은 보존한다")
    void hidesBlockedActorAcrossAllNotificationQueries(boolean reverse) {
        UUID hidden = notice(blocked, otherFeed, 3);
        UUID visible = notice(other, otherFeed, 2);
        block(reverse ? blocked : viewer, reverse ? viewer : blocked);

        assertThat(notifications.findAfter(viewer, Optional.empty(), 1))
                .extracting(NotificationSnapshot::id).containsExactly(visible);
        assertThat(notifications.countUnread(viewer)).isEqualTo(1);
        assertThat(notifications.findVisibleById(hidden)).isEmpty();
        assertThat(transactions.<Boolean>execute(status -> notifications.markRead(hidden, viewer, TIME.plusSeconds(10))))
                .isFalse();
        assertThatThrownBy(() -> notificationService.markRead(viewer, hidden))
                .isInstanceOf(NotificationNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_user_id = ?", Long.class,
                viewer)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT read_at FROM notifications WHERE id = ?", Timestamp.class, hidden))
                .isNull();

        unblock(reverse ? blocked : viewer, reverse ? viewer : blocked);

        assertThat(notifications.findVisibleById(hidden)).isPresent();
        assertThat(notifications.countUnread(viewer)).isEqualTo(2);
        notificationService.markRead(viewer, hidden);
        assertThat(notifications.countUnread(viewer)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("행동자가 달라도 피드 작성자와 차단 관계인 알림은 모든 조회와 읽음에서 숨긴다")
    void hidesNotificationsOfBlockedFeedAuthor(boolean reverse) {
        UUID hidden = notice(other, blockedFeed, 2);
        UUID visible = notice(other, otherFeed, 1);
        block(reverse ? blocked : viewer, reverse ? viewer : blocked);

        assertThat(notifications.findAfter(viewer, Optional.empty(), 1))
                .extracting(NotificationSnapshot::id).containsExactly(visible);
        assertThat(notifications.countUnread(viewer)).isEqualTo(1);
        assertThat(notifications.findVisibleById(hidden)).isEmpty();
        assertThat(transactions.<Boolean>execute(status -> notifications.markRead(hidden, viewer, TIME.plusSeconds(10))))
                .isFalse();
    }

    @Test
    @DisplayName("행동자 연결이 없는 과거 알림은 관련 없는 차단 때문에 사라지지 않는다")
    void keepsNullableActorNotificationsVisible() {
        UUID historical = notice(null, otherFeed, 1);
        block(viewer, blocked);

        assertThat(notifications.findAfter(viewer, Optional.empty(), 1))
                .extracting(NotificationSnapshot::id).containsExactly(historical);
        assertThat(notifications.countUnread(viewer)).isEqualTo(1);
        assertThat(notifications.findVisibleById(historical)).isPresent();
        assertThat(transactions.<Boolean>execute(status -> notifications.markRead(historical, viewer, TIME.plusSeconds(10))))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("공유 상세도 차단 관계에서는 숨기고 익명과 차단 해제 후에는 같은 결과를 반환한다")
    void appliesBlockingToShareQueries(boolean reverse) {
        block(reverse ? blocked : viewer, reverse ? viewer : blocked);

        assertThatThrownBy(() -> shares.getPublicShare(blockedShare, viewer))
                .isInstanceOf(ShareNotFoundException.class);
        assertThat(shares.getPublicShare(blockedShare, null).title()).isEqualTo("공유 제목");
        unblock(reverse ? blocked : viewer, reverse ? viewer : blocked);
        assertThat(shares.getPublicShare(blockedShare, viewer).title()).isEqualTo("공유 제목");
    }

    @Test
    @DisplayName("차단한 악성 콘텐츠도 신고할 수 있고 본인 접수만 재사용하며 최초 신고 내용은 보존한다")
    void permitsReportingBlockedContentWithoutSharingAnotherReportersReceipt() {
        block(viewer, blocked);
        ReportSubmission firstSubmission = new ReportSubmission(ReportTargetType.FEED, blockedFeed,
                ReportReason.SPAM, "차단 후 접수한 최초 설명");

        ReportReceipt initial = reportService.submit(viewer, firstSubmission);
        fixtureReports.add(initial.id());
        assertThat(initial.duplicate()).isFalse();
        ReportReceipt replay = reportService.submit(viewer, new ReportSubmission(ReportTargetType.FEED, blockedFeed,
                ReportReason.HARASSMENT, "덮어쓰면 안 되는 재접수 설명"));
        assertThat(replay).isEqualTo(new ReportReceipt(initial.id(), true));
        ReportReceipt another = reportService.submit(other, firstSubmission);
        fixtureReports.add(another.id());
        assertThat(another.duplicate()).isFalse();
        assertThat(another.id()).isNotEqualTo(initial.id());
        assertThatThrownBy(() -> reportService.submit(blocked, firstSubmission))
                .isInstanceOf(InvalidReportException.class);
        assertThat(feedQueries.findActiveSnapshotById(blockedFeed, viewer)).isEmpty();
        jdbc.update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", blockedFeed);

        assertThat(reportService.submit(viewer, firstSubmission)).isEqualTo(new ReportReceipt(initial.id(), true));
        assertThat(jdbc.queryForMap("""
                SELECT reporter_id, target_user_id, reason, description FROM reports WHERE id = ?
                """, initial.id()))
                .containsEntry("reporter_id", viewer)
                .containsEntry("target_user_id", blocked)
                .containsEntry("reason", "SPAM")
                .containsEntry("description", "차단 후 접수한 최초 설명");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reports WHERE target_type = 'FEED' AND target_id = ?",
                Long.class, blockedFeed)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE feed_id = ?", Long.class,
                blockedFeed)).isZero();
        assertThat(jdbc.queryForObject("SELECT like_count FROM feeds WHERE id = ?", Integer.class, blockedFeed))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT comment_count FROM feeds WHERE id = ?", Integer.class, blockedFeed))
                .isZero();
    }

    private UUID member() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, name, nickname, profile_image_code) VALUES (?, '회원', ?, 1)", id,
                "회원" + users.size());
        jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'KAKAO', ?)",
                id, id.toString());
        users.add(id);
        return id;
    }

    private UUID feed(UUID authorId, int position) {
        UUID diary = UUID.randomUUID();
        UUID feed = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, DATE '2026-10-22', '원문')",
                diary, authorId);
        jdbc.update("""
                INSERT INTO diary_generations
                    (id, diary_id, prompt_id, idempotency_key, request_fingerprint, status, title, image_object_key)
                VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', '공유 제목', ?)
                """, UUID.randomUUID(), diary, promptId, UUID.randomUUID(), "b".repeat(64),
                "generated/block-visibility/" + diary + ".webp");
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id, published_at, like_count) VALUES (?, ?, ?, ?, ?)",
                feed, diary, categoryId, Timestamp.from(TIME.plusSeconds(position)), position);
        return feed;
    }

    private UUID comment(UUID authorId, UUID feedId, int position) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO comments (id, feed_id, author_id, content, created_at) VALUES (?, ?, ?, '댓글', ?)",
                id, feedId, authorId, Timestamp.from(TIME.plusSeconds(position)));
        jdbc.update("UPDATE feeds SET comment_count = comment_count + 1 WHERE id = ?", feedId);
        return id;
    }

    private UUID notice(UUID actorId, UUID feedId, int position) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications
                    (id, recipient_user_id, actor_user_id, feed_id, type, source_action_id, created_at)
                VALUES (?, ?, ?, ?, 'COMMENT', ?, ?)
                """, id, viewer, actorId, feedId, UUID.randomUUID(), Timestamp.from(TIME.plusSeconds(position)));
        return id;
    }

    private void block(UUID blockerId, UUID blockedId) {
        jdbc.update("INSERT INTO user_blocks (id, blocker_id, blocked_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), blockerId, blockedId);
    }

    private void unblock(UUID blockerId, UUID blockedId) {
        jdbc.update("DELETE FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?", blockerId, blockedId);
    }
}
