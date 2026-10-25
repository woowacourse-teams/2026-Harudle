package com.harudle.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.harudle.comment.service.CommentService;
import com.harudle.feed.service.FeedInteractionService;
import com.harudle.feed.service.FeedLikeService;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.moderation.domain.ModerationChange;
import com.harudle.moderation.domain.ModerationTargetType;
import com.harudle.moderation.service.AdminModerationService;
import com.harudle.support.AdminModerationDatabaseTestSupport;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ")
class AdminModerationInteractionPersistenceTest extends AdminModerationDatabaseTestSupport {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminModerationService moderation;
    @Autowired private FeedLikeService likes;
    @Autowired private CommentService comments;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private FeedInteractionService interactions;

    private UUID admin;
    private UUID author;
    private UUID viewer;
    private UUID diary;
    private UUID feed;
    private Long prompt;

    @BeforeEach
    void setUp() {
        admin = user(true);
        author = user(false);
        viewer = user(false);
        prompt = jdbc.queryForObject("""
                INSERT INTO generation_prompts(storyboard_prompt_text,image_style_prompt_text,image_asset_object_key)
                VALUES ('경합 테스트','경합 테스트',?) RETURNING id
                """, Long.class, "references/moderation-race-" + UUID.randomUUID());
        diary = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries(id,user_id,diary_date,source_text) VALUES (?,?,CURRENT_DATE,'개인 원문')",
                diary, author);
        jdbc.update("""
                INSERT INTO diary_generations(id,diary_id,prompt_id,idempotency_key,request_fingerprint,
                    status,title,image_object_key,completed_at)
                VALUES (?,?,?,?,?,'SUCCEEDED','공개 제목',?,CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), diary, prompt, UUID.randomUUID(), "c".repeat(64),
                "generated/moderation-race-" + UUID.randomUUID() + ".webp");
        feed = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO feeds(id,diary_id,category_id,comment_count)
                VALUES (?, ?, (SELECT id FROM categories WHERE name='일상'),1)
                """, feed, diary);
        jdbc.update("INSERT INTO comments(id,feed_id,author_id,content) VALUES (?,?,?,'기존 댓글')",
                UUID.randomUUID(), feed, viewer);
    }

    @AfterEach
    void cleanUp() {
        if (feed != null) {
            jdbc.update("DELETE FROM moderation_events WHERE target_id=?", feed);
            jdbc.update("DELETE FROM moderation_states WHERE target_id=?", feed);
        }
        for (UUID user : List.of(admin, author, viewer)) {
            jdbc.update("DELETE FROM users WHERE id=?", user);
        }
        if (prompt != null) {
            jdbc.update("DELETE FROM generation_prompts WHERE id=?", prompt);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"like", "unlike", "comment"})
    @DisplayName("RR 연결 풀에서도 실제 접촉 TX는 RC이며 관리자 숨김 커밋 후 새 상태를 읽고 저장을 거절한다")
    void rejectsInteractionWaitingForModerationCommitEvenWithRepeatableReadPool(String operation) throws Exception {
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
        int initialLikes = operation.equals("unlike") ? 1 : 0;
        UUID originalLike = UUID.randomUUID();
        if (initialLikes == 1) {
            jdbc.update("INSERT INTO feed_likes(id,feed_id,user_id) VALUES (?,?,?)", originalLike, feed, viewer);
            jdbc.update("UPDATE feeds SET like_count=1 WHERE id=?", feed);
            jdbc.update("""
                    INSERT INTO notifications(id,recipient_user_id,actor_user_id,feed_id,type,source_action_id)
                    VALUES (?,?,?,?,'LIKE',?)
                    """, UUID.randomUUID(), author, viewer, feed, originalLike);
        }
        CountDownLatch hiddenUncommitted = new CountDownLatch(1);
        CountDownLatch releaseModeration = new CountDownLatch(1);
        CountDownLatch interactionStarted = new CountDownLatch(1);
        AtomicBoolean capture = new AtomicBoolean(true);
        int[] interactionPid = new int[1];
        FeedInteractionService spyTarget = AopTestUtils.getUltimateTargetObject(interactions);
        doAnswer(invocation -> {
            if (feed.equals(invocation.getArgument(0)) && viewer.equals(invocation.getArgument(1))
                    && capture.compareAndSet(true, false)) {
                assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
                interactionPid[0] = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                interactionStarted.countDown();
            }
            return invocation.callRealMethod();
        }).when(spyTarget).lockActiveForInteraction(any(UUID.class), any(UUID.class));
        var moderatorTransaction = new TransactionTemplate(transactionManager);
        moderatorTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> moderator = executor.submit(() -> moderatorTransaction.executeWithoutResult(status -> {
                moderation.change(admin, ModerationTargetType.FEED, feed,
                        new ModerationChange(true, 0, "공개 접촉 차단"));
                hiddenUncommitted.countDown();
                await(releaseModeration);
            }));
            assertThat(hiddenUncommitted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Object> interaction = executor.submit(() -> {
                try {
                    return switch (operation) {
                        case "like" -> likes.like(viewer, feed);
                        case "unlike" -> likes.unlike(viewer, feed);
                        case "comment" -> comments.create(viewer, feed, "저장되면 안 되는 댓글");
                        default -> throw new AssertionError("지원하지 않는 접촉 동작");
                    };
                } catch (FeedNotFoundException exception) {
                    return exception;
                }
            });
            assertThat(interactionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlockedByAnotherTransaction(interactionPid[0]);
            releaseModeration.countDown();
            moderator.get(5, TimeUnit.SECONDS);
            assertThat(interaction.get(5, TimeUnit.SECONDS)).isInstanceOf(FeedNotFoundException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_likes WHERE feed_id=?", Integer.class, feed))
                    .isEqualTo(initialLikes);
            assertThat(jdbc.queryForObject("SELECT like_count FROM feeds WHERE id=?", Integer.class, feed))
                    .isEqualTo(initialLikes);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM comments WHERE feed_id=?", Integer.class, feed))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT comment_count FROM feeds WHERE id=?", Integer.class, feed))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE feed_id=?", Integer.class, feed))
                    .isEqualTo(initialLikes);
            if (initialLikes == 1) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_likes WHERE id=?", Integer.class, originalLike))
                        .isEqualTo(1);
            }
        } finally {
            releaseModeration.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private UUID user(boolean administrator) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,name,nickname,profile_image_code,role) VALUES (?,'경합회원',?,1,?)",
                id, "경합" + id.toString().substring(0, 8), administrator ? "ADMIN" : "USER");
        jdbc.update("INSERT INTO oauth_accounts(user_id,provider,provider_subject) VALUES (?,'KAKAO',?)", id, id.toString());
        return id;
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
        throw new AssertionError("실제 접촉 트랜잭션이 관리자 숨김 잠금에 대기하지 않았습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("숨김 경합 테스트 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("숨김 경합 테스트 중단");
        }
    }
}
