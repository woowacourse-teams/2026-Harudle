package com.harudle.moderation.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.comment.repository.CommentRepository;
import com.harudle.comment.service.CommentService;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.feed.query.FeedSort;
import com.harudle.feed.repository.FeedListRepository;
import com.harudle.feed.repository.FeedRepository;
import com.harudle.feed.service.FeedLikeService;
import com.harudle.feed.service.FeedPublicationService;
import com.harudle.feed.service.exception.ContentRestrictedException;
import com.harudle.feed.service.FeedQueryService;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.notification.repository.NotificationRepository;
import com.harudle.report.domain.ReportTargetType;
import com.harudle.report.repository.ReportTargetReader;
import com.harudle.share.repository.ShareLinkRepository;
import com.harudle.support.AdminModerationDatabaseTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class PublicContentModerationPersistenceTest extends AdminModerationDatabaseTestSupport {
    private static final Instant PUBLISHED = Instant.parse("2100-01-01T00:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FeedListRepository feedLists;
    @Autowired private FeedRepository feeds;
    @Autowired private FeedQueryService feedDetails;
    @Autowired private CommentRepository comments;
    @Autowired private CommentService commentService;
    @Autowired private FeedLikeService likes;
    @Autowired private FeedPublicationService publication;
    @Autowired private DiaryRepository diaries;
    @Autowired private ShareLinkRepository shares;
    @Autowired private ReportTargetReader reportTargets;
    @Autowired private NotificationRepository notifications;

    private UUID author;
    private UUID actor;
    private UUID diary;
    private UUID feed;
    private UUID generation;
    private UUID comment;
    private long category;
    private long prompt;

    @BeforeEach
    void setUp() {
        author = member();
        actor = member();
        category = jdbc.queryForObject("SELECT id FROM categories WHERE name = '일상'", Long.class);
        prompt = jdbc.queryForObject("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('스토리', '스타일', ?) RETURNING id
                """, Long.class, "references/moderation-" + UUID.randomUUID() + ".png");
        diary = UUID.randomUUID();
        generation = UUID.randomUUID();
        feed = completedFeed(diary, generation, PUBLISHED);
        comment = comment(feed, actor, PUBLISHED);
        jdbc.update("UPDATE feeds SET comment_count = 1 WHERE id = ?", feed);
    }

    @Test
    @DisplayName("숨긴 피드는 목록 제한 전에 제외하고 익명 상세와 회원 상세 모두 차단하며 해제하면 다시 보인다")
    void filterHiddenFeedsBeforeLimitForEveryViewerAndRestoreThemOnRelease() {
        UUID older = completedFeed(UUID.randomUUID(), UUID.randomUUID(), PUBLISHED.minusSeconds(1));
        restrict("FEED", feed, true);

        assertThat(feedLists.findAfter(FeedSort.LATEST, category, null, 1, null))
                .extracting(item -> item.id()).containsExactly(older);
        assertThat(feeds.findActiveSnapshotById(feed)).isEmpty();
        assertThat(feeds.findVisibleSnapshotById(feed, actor)).isEmpty();
        assertThatThrownBy(() -> feedDetails.getDetail(null, feed)).isInstanceOf(FeedNotFoundException.class);
        assertThatThrownBy(() -> feedDetails.getDetail(actor, feed)).isInstanceOf(FeedNotFoundException.class);

        restrict("FEED", feed, false);
        assertThat(feedLists.findAfter(FeedSort.LATEST, category, null, 1, null))
                .extracting(item -> item.id()).containsExactly(feed);
        assertThat(feeds.findActiveSnapshotById(feed)).isPresent();
    }

    @Test
    @DisplayName("댓글 숨김은 댓글 페이지 제한 전에 제외하고 부모 피드 숨김은 댓글 조회 전체를 막는다")
    void filterHiddenCommentsBeforeLimitAndRejectTheirHiddenParent() {
        UUID next = comment(feed, actor, PUBLISHED.plusSeconds(1));
        restrict("COMMENT", comment, true);

        assertThat(comments.findAfter(feed, Optional.empty(), 1))
                .extracting(item -> item.id()).containsExactly(next);
        assertThat(comments.existsVisibleFeed(feed, null)).isTrue();
        restrict("COMMENT", comment, false);
        assertThat(comments.findAfter(feed, Optional.empty(), 1))
                .extracting(item -> item.id()).containsExactly(comment);

        restrict("FEED", feed, true);
        assertThat(comments.existsVisibleFeed(feed, null)).isFalse();
        assertThat(comments.existsVisibleFeed(feed, actor)).isFalse();
        assertThat(comments.findAfter(feed, Optional.empty(), 50)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"comment", "like", "unlike"})
    @DisplayName("숨긴 피드에서는 댓글 작성과 좋아요 추가 및 취소가 원본과 알림을 변경하지 않는다")
    void rejectContactWithHiddenFeedsWithoutChangingRowsOrCounters(String operation) {
        UUID like = UUID.randomUUID();
        jdbc.update("INSERT INTO feed_likes (id, feed_id, user_id) VALUES (?, ?, ?)", like, feed, actor);
        jdbc.update("UPDATE feeds SET like_count = 1 WHERE id = ?", feed);
        restrict("FEED", feed, true);

        assertThatThrownBy(() -> {
            switch (operation) {
                case "comment" -> commentService.create(actor, feed, "새 댓글");
                case "like" -> likes.like(actor, feed);
                case "unlike" -> likes.unlike(actor, feed);
                default -> throw new AssertionError("지원하지 않는 접촉 동작");
            }
        }).isInstanceOf(FeedNotFoundException.class);

        assertThat(number("SELECT count(*) FROM comments WHERE feed_id = ?", feed)).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM feed_likes WHERE feed_id = ?", feed)).isEqualTo(1);
        assertThat(number("SELECT like_count FROM feeds WHERE id = ?", feed)).isEqualTo(1);
        assertThat(number("SELECT comment_count FROM feeds WHERE id = ?", feed)).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM notifications WHERE feed_id = ?", feed)).isZero();
    }

    @Test
    @DisplayName("숨긴 댓글과 부모 피드에서도 작성자는 자신의 댓글을 삭제할 수 있고 숨김 해제는 삭제를 되돌리지 않는다")
    void allowAuthorCleanupWithoutRestoringDeletedCommentsWhenModerationIsReleased() {
        restrict("COMMENT", comment, true);
        restrict("FEED", feed, true);

        commentService.delete(actor, comment);

        assertThat(comments.findActiveById(comment)).isEmpty();
        assertThat(number("SELECT comment_count FROM feeds WHERE id = ?", feed)).isZero();
        restrict("COMMENT", comment, false);
        restrict("FEED", feed, false);
        assertThat(comments.findAfter(feed, Optional.empty(), 50)).isEmpty();
        assertThat(diaries.findActiveSnapshotById(diary)).isPresent();
    }

    @Test
    @DisplayName("피드가 나중에 삭제돼도 관리자 숨김은 공개 공유를 막고 개인 일기 원문 조회는 유지한다")
    void retainShareRestrictionAfterFeedDeletionWithoutHidingPrivateDiary() {
        UUID share = UUID.randomUUID();
        jdbc.update("INSERT INTO share_links (id, generation_id) VALUES (?, ?)", share, generation);
        assertThat(shares.findPublicSnapshotById(share)).isPresent();
        restrict("FEED", feed, true);
        jdbc.update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", feed);

        assertThat(shares.findPublicSnapshotById(share)).isEmpty();
        assertThat(diaries.findActiveSnapshotById(diary)).isPresent();
        restrict("FEED", feed, false);
        assertThat(shares.findPublicSnapshotById(share)).isPresent();
        assertThat(feeds.findActiveSnapshotById(feed)).isEmpty();
    }

    @Test
    @DisplayName("숨긴 피드를 삭제하고 같은 일기를 재게시해도 공개 조회와 접촉을 우회할 수 없다")
    void restrictRepublishedFeedsFromTheSameDiaryAndRejectAnotherPublication() {
        restrict("FEED", feed, true);
        jdbc.update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", feed);
        assertThatThrownBy(() -> publication.publish(author, diary, category))
                .isInstanceOf(ContentRestrictedException.class);
        UUID replacement = UUID.randomUUID();
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id, published_at) VALUES (?, ?, ?, ?)",
                replacement, diary, category, Timestamp.from(PUBLISHED.plusSeconds(1)));
        UUID replacementComment = comment(replacement, actor, PUBLISHED.plusSeconds(1));

        assertThat(feeds.findActiveSnapshotById(replacement)).isEmpty();
        assertThat(feedLists.findAfter(FeedSort.LATEST, category, null, 50, null))
                .noneMatch(item -> item.id().equals(replacement));
        assertThat(comments.existsVisibleFeed(replacement, null)).isFalse();
        assertThat(comments.findAfter(replacement, Optional.empty(), 50)).isEmpty();
        assertThat(reportTargets.findVisibleTargetAuthorId(actor, ReportTargetType.FEED, replacement)).isEmpty();
        assertThat(reportTargets.findVisibleTargetAuthorId(actor, ReportTargetType.COMMENT, replacementComment))
                .isEmpty();
        assertThatThrownBy(() -> likes.like(actor, replacement)).isInstanceOf(FeedNotFoundException.class);
        assertThat(diaries.findActiveSnapshotById(diary)).isPresent();
    }

    @Test
    @DisplayName("관리자 숨김은 새 콘텐츠 신고 대상에서 제외하며 사용자 신고 대상과 원본은 보존한다")
    void omitHiddenContentFromNewReportTargetsAndRestoreItWithoutMutatingTheSource() {
        restrict("COMMENT", comment, true);
        assertThat(reportTargets.findVisibleTargetAuthorId(actor, ReportTargetType.COMMENT, comment)).isEmpty();
        assertThat(reportTargets.findVisibleTargetAuthorId(actor, ReportTargetType.FEED, feed)).contains(author);
        restrict("COMMENT", comment, false);
        assertThat(reportTargets.findVisibleTargetAuthorId(actor, ReportTargetType.COMMENT, comment)).contains(actor);
        restrict("FEED", feed, true);
        assertThat(reportTargets.findVisibleTargetAuthorId(actor, ReportTargetType.FEED, feed)).isEmpty();
        assertThat(reportTargets.findVisibleTargetAuthorId(actor, ReportTargetType.COMMENT, comment)).isEmpty();
        assertThat(reportTargets.findVisibleTargetAuthorId(actor, ReportTargetType.USER, author)).contains(author);
        assertThat(comments.findActiveById(comment)).isPresent();
    }

    @Test
    @DisplayName("숨긴 댓글과 피드의 기존 알림은 보존하되 목록과 미읽음 및 읽음 처리에서 제외하고 해제 시 재노출한다")
    void preserveNotificationHistoryWhileApplyingModerationToEveryReadOperation() {
        UUID notification = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, recipient_user_id, actor_user_id, feed_id, type, source_action_id)
                VALUES (?, ?, ?, ?, 'COMMENT', ?)
                """, notification, author, actor, feed, comment);
        assertThat(notifications.countUnread(author)).isEqualTo(1);
        restrict("COMMENT", comment, true);
        assertHiddenNotification(notification);
        restrict("COMMENT", comment, false);
        assertThat(notifications.findAfter(author, Optional.empty(), 1)).hasSize(1);
        assertThat(notifications.countUnread(author)).isEqualTo(1);
        assertThat(notifications.markRead(notification, author, PUBLISHED)).isTrue();
        restrict("FEED", feed, true);
        assertHiddenNotification(notification);
        assertThat(number("SELECT count(*) FROM notifications WHERE id = ?", notification)).isEqualTo(1);
        restrict("FEED", feed, false);
        assertThat(notifications.findVisibleById(notification)).isPresent();
        assertThat(notifications.countUnread(author)).isZero();
    }

    private void assertHiddenNotification(UUID id) {
        assertThat(notifications.findAfter(author, Optional.empty(), 1)).isEmpty();
        assertThat(notifications.countUnread(author)).isZero();
        assertThat(notifications.findVisibleById(id)).isEmpty();
        assertThat(notifications.markRead(id, author, PUBLISHED)).isFalse();
    }

    private UUID member() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, name, nickname, profile_image_code) VALUES (?, '회원', ?, 1)",
                id, "관리" + id.toString().substring(0, 8));
        jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'KAKAO', ?)",
                id, id.toString());
        return id;
    }

    private UUID completedFeed(UUID diaryId, UUID generationId, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '개인 원문')",
                diaryId, author);
        jdbc.update("""
                INSERT INTO diary_generations
                    (id, diary_id, prompt_id, idempotency_key, request_fingerprint, status, image_object_key)
                VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', ?)
                """, generationId, diaryId, prompt, UUID.randomUUID(), "a".repeat(64),
                "generated/moderation-" + generationId + ".webp");
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id, published_at) VALUES (?, ?, ?, ?)",
                id, diaryId, category, Timestamp.from(at));
        return id;
    }

    private UUID comment(UUID parent, UUID writer, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO comments (id, feed_id, author_id, content, created_at) VALUES (?, ?, ?, '댓글', ?)",
                id, parent, writer, Timestamp.from(at));
        return id;
    }

    private void restrict(String type, UUID id, boolean restricted) {
        jdbc.update("""
                INSERT INTO moderation_states (target_type, target_id, restricted, version, reason, changed_at)
                VALUES (?, ?, ?, 1, '심사 결과', CURRENT_TIMESTAMP)
                ON CONFLICT (target_type, target_id) DO UPDATE SET restricted = EXCLUDED.restricted,
                    version = moderation_states.version + 1
                """, type, id, restricted);
    }

    private long number(String sql, UUID id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }
}
