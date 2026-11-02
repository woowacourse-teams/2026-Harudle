package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.support.ServiceIntegrationTestSupport;
import com.harudle.admin.repository.AdminReportRepository;
import com.harudle.auth.domain.OAuthProvider;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class WithdrawalDatabaseServiceTest extends ServiceIntegrationTestSupport {

    @Autowired private WithdrawalDatabaseService service;
    @Autowired private WithdrawalService withdrawal;
    @Autowired private OAuthLoginService login;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private AdminReportRepository reports;
    private final UUID userId = UUID.randomUUID();
    private final UUID reporterId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();
    private final UUID commentId = UUID.randomUUID();
    private final UUID ownFeedId = UUID.randomUUID();
    private final UUID ownFeedCommentId = UUID.randomUUID();
    private Long promptId;
    private UUID newUserId;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM profile_image_cleanup_tasks WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM reports WHERE id = ?", reportId);
        jdbc.update("DELETE FROM moderation_events WHERE target_id IN (?, ?, ?, ?)",
                userId, commentId, ownFeedId, ownFeedCommentId);
        jdbc.update("DELETE FROM moderation_states WHERE target_id IN (?, ?, ?, ?)",
                userId, commentId, ownFeedId, ownFeedCommentId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", reporterId);
        if (newUserId != null) {
            jdbc.update("DELETE FROM users WHERE id = ?", newUserId);
        }
        if (promptId != null) {
            jdbc.update("DELETE FROM generation_prompts WHERE id = ?", promptId);
        }
    }

    @Test
    void deletesUserAndProfileCleanupTasksAfterCollectingImageKeys() {
        insertUserAndTask();

        var targets = service.delete(userId);

        assertThat(targets.profileImages()).containsExactlyInAnyOrder("profile/current.webp", "profile/old.webp");
        assertThat(count("users", "id")).isZero();
        assertThat(count("profile_image_cleanup_tasks", "user_id")).isZero();
    }

    @Test
    void rollsBackUserAndCleanupTasksTogetherWhenTransactionFails() {
        insertUserAndTask();
        insertRestriction("USER", userId);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.delete(userId);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("users", "id")).isEqualTo(1);
        assertThat(count("profile_image_cleanup_tasks", "user_id")).isEqualTo(1);
        assertThat(count("moderation_states", "target_id")).isEqualTo(1);
        assertThat(count("moderation_events", "target_id")).isEqualTo(1);
    }

    @Test
    void keepsReportAndRemovesTargetMemberIdentifiers() {
        insertUserAndTask();
        jdbc.update("INSERT INTO users (id, name) VALUES (?, ?)", reporterId, "신고자");
        jdbc.update("INSERT INTO reports (id, reporter_id, target_type, target_id, target_user_id, reason) "
                        + "VALUES (?, ?, 'USER', ?, ?, 'SPAM')", reportId, reporterId, userId, userId);

        service.delete(userId);

        var report = reports.findById(reportId).orElseThrow();
        assertThat(report.targetId()).isNull();
        assertThat(report.targetUserId()).isNull();
        assertThat(reports.findTarget(report.targetType(), report.targetId()).exists()).isFalse();
    }

    @Test
    void sameSocialAccountAfterDeletionCreatesNewMember() {
        insertUserAndTask();
        jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'GOOGLE', ?)",
                userId, "google-rejoin-user");

        service.delete(userId);
        var result = login.login(new OAuthLoginCommand(OAuthProvider.GOOGLE, "google-rejoin-user",
                "rejoin@example.com", "회원"), Instant.now());
        newUserId = result.userId();

        assertThat(newUserId).isNotEqualTo(userId);
        assertThat(count("users", "id")).isZero();
    }

    @Test
    void removesOnlyMemberRestrictionAndKeepsCommentRestriction() {
        insertUserAndTask();
        insertRestriction("USER", userId);
        insertRestriction("COMMENT", commentId);

        service.delete(userId);

        assertThat(count("moderation_states", "target_id")).isZero();
        assertThat(count("moderation_events", "target_id")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM moderation_states WHERE target_id = ?",
                Integer.class, commentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM moderation_events WHERE target_id = ?",
                Integer.class, commentId)).isEqualTo(1);
    }

    @Test
    void preservesOtherMembersFeedCommentsLikesAndBlockedVisibility() {
        insertUserAndTask();
        jdbc.update("INSERT INTO users (id, name) VALUES (?, ?)", reporterId, "다른 회원");
        UUID diaryId = UUID.randomUUID();
        UUID feedId = UUID.randomUUID();
        UUID likeId = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '일기')",
                diaryId, reporterId);
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, (SELECT id FROM categories LIMIT 1))",
                feedId, diaryId);
        jdbc.update("INSERT INTO comments (id, feed_id, author_id, content) VALUES (?, ?, ?, '남은 댓글')",
                commentId, feedId, userId);
        jdbc.update("INSERT INTO feed_likes (id, feed_id, user_id) VALUES (?, ?, ?)", likeId, feedId, userId);
        jdbc.update("INSERT INTO user_blocks (id, blocker_id, blocked_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), userId, reporterId);

        service.delete(userId);

        assertThat(jdbc.queryForObject("SELECT author_id FROM comments WHERE id = ?", UUID.class, commentId)).isNull();
        assertThat(jdbc.queryForObject("SELECT user_id FROM feed_likes WHERE id = ?", UUID.class, likeId)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM withdrawn_comment_hidden_viewers "
                + "WHERE comment_id = ? AND viewer_id = ?", Integer.class, commentId, reporterId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feeds WHERE id = ?", Integer.class, feedId)).isEqualTo(1);
    }

    @Test
    void removesModerationForDeletedFeedAndItsComments() {
        insertUserAndTask();
        jdbc.update("INSERT INTO users (id, name) VALUES (?, ?)", reporterId, "다른 회원");
        UUID diaryId = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '일기')",
                diaryId, userId);
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, (SELECT id FROM categories LIMIT 1))",
                ownFeedId, diaryId);
        jdbc.update("INSERT INTO comments (id, feed_id, author_id, content) VALUES (?, ?, ?, '댓글')",
                ownFeedCommentId, ownFeedId, reporterId);
        insertRestriction("FEED", ownFeedId);
        insertRestriction("COMMENT", ownFeedCommentId);
        insertRestriction("COMMENT", commentId);

        service.delete(userId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM moderation_states WHERE target_id IN (?, ?)",
                Integer.class, ownFeedId, ownFeedCommentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM moderation_events WHERE target_id IN (?, ?)",
                Integer.class, ownFeedId, ownFeedCommentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM moderation_states WHERE target_id = ?",
                Integer.class, commentId)).isEqualTo(1);
    }

    @Test
    void appleRevocationDeletesEvenWhenGenerationIsPending() {
        insertUserAndTask();
        jdbc.update("UPDATE users SET profile_image_object_key = NULL WHERE id = ?", userId);
        jdbc.update("DELETE FROM profile_image_cleanup_tasks WHERE user_id = ?", userId);
        jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'APPLE', 'apple-user')",
                userId);
        UUID diaryId = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '일기')",
                diaryId, userId);
        promptId = jdbc.queryForObject("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('스토리보드', '스타일', 'references/apple-revocation.png') RETURNING id
                """, Long.class);
        jdbc.update("""
                INSERT INTO diary_generations (id, diary_id, prompt_id, idempotency_key, request_fingerprint, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                """, UUID.randomUUID(), diaryId, promptId, UUID.randomUUID(), "a".repeat(64));

        withdrawal.completeAppleRevocation("apple-user", Instant.now().plusSeconds(5));
        assertThat(count("users", "id")).isZero();
    }

    @Test
    void oldAppleRevocationDoesNotDeleteNewAccount() {
        insertUserAndTask();
        jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'APPLE', 'apple-user')",
                userId);

        withdrawal.completeAppleRevocation("apple-user", Instant.now().minusSeconds(60));
        assertThat(count("users", "id")).isEqualTo(1);
    }

    private void insertRestriction(String type, UUID targetId) {
        jdbc.update("INSERT INTO moderation_states (target_type, target_id, restricted, version, reason, changed_at) "
                + "VALUES (?, ?, true, 1, '사유', CURRENT_TIMESTAMP)", type, targetId);
        jdbc.update("INSERT INTO moderation_events (id, target_type, target_id, restricted, version, reason, changed_at) "
                + "VALUES (?, ?, ?, true, 1, '사유', CURRENT_TIMESTAMP)", UUID.randomUUID(), type, targetId);
    }

    private void insertUserAndTask() {
        jdbc.update("INSERT INTO users (id, name, profile_image_object_key) VALUES (?, ?, ?)",
                userId, "회원", "profile/current.webp");
        jdbc.update("INSERT INTO profile_image_cleanup_tasks (user_id, object_key, created_at, delete_after) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", userId, "profile/old.webp");
    }

    private int count(String table, String column) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, userId);
        return count == null ? 0 : count;
    }
}
