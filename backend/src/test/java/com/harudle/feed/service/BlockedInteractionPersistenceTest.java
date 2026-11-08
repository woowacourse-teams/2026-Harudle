package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

import com.harudle.block.service.BlockInteractionPolicy;
import com.harudle.block.service.BlockService;
import com.harudle.comment.service.CommentService;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.push.service.port.FeedPushPublicationGuard;
import com.harudle.support.BlockDatabaseTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

@SpringBootTest(properties = {
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "harudle.generation.adapters.enabled=false",
        "harudle.profile.image-base-url=https://test.example.com/profiles",
        "KAKAO_REST_API_KEY=block-test-client",
        "KAKAO_CLIENT_SECRET=block-test-secret",
        "JWT_SECRET_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class BlockedInteractionPersistenceTest extends BlockDatabaseTestSupport {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FeedLikeService likes;
    @Autowired private CommentService comments;
    @Autowired private BlockService blocks;
    @Autowired private FeedPublicationService publication;
    @MockitoSpyBean private FeedPushPublicationGuard publicationGuard;
    @MockitoSpyBean private BlockInteractionPolicy policy;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean(enforceOverride = true) private DiaryGenerationWorker generationWorker;

    private final List<UUID> users = new ArrayList<>();
    private final List<Long> prompts = new ArrayList<>();
    private UUID viewer;
    private UUID author;
    private UUID feed;

    @BeforeEach
    void setUp() {
        viewer = member();
        author = member();
        UUID diary = UUID.randomUUID();
        feed = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, DATE '2026-10-22', '원문')",
                diary, author);
        jdbc.update("""
                INSERT INTO feeds (id, diary_id, category_id)
                VALUES (?, ?, (SELECT id FROM categories WHERE name = '일상'))
                """, feed, diary);
    }

    @AfterEach
    void tearDown() {
        users.forEach(user -> jdbc.update("DELETE FROM users WHERE id = ?", user));
        users.clear();
        prompts.forEach(prompt -> jdbc.update("DELETE FROM generation_prompts WHERE id = ?", prompt));
        prompts.clear();
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    @DisplayName("풀 기본격리가 RR이어도 쓰기는 RC로 시작하여 먼저 커밋된 역방향 차단을 읽고 저장을 거절한다")
    void rejectsInteractionAfterConcurrentBlockEvenWhenPoolDefaultsToRepeatableRead(Action action) throws Exception {
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
        UUID existingLike = UUID.randomUUID();
        int initialLikeCount = action == Action.UNLIKE ? 1 : 0;
        if (action == Action.UNLIKE) {
            jdbc.update("INSERT INTO feed_likes (id, feed_id, user_id) VALUES (?, ?, ?)", existingLike, feed, viewer);
            jdbc.update("UPDATE feeds SET like_count = 1 WHERE id = ?", feed);
        }
        var policyReached = new CountDownLatch(1);
        var resumePolicy = new CountDownLatch(1);
        var pauseOnce = new AtomicBoolean(true);
        var actualIsolation = new AtomicReference<String>();
        BlockInteractionPolicy policyTarget = AopTestUtils.getUltimateTargetObject(policy);
        doAnswer(invocation -> {
            if (pauseOnce.compareAndSet(true, false)) {
                actualIsolation.set(jdbc.queryForObject("SHOW transaction_isolation", String.class));
                policyReached.countDown();
                if (!resumePolicy.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("차단 정책 확인 재개 신호를 기다리지 못했습니다.");
                }
            }
            return invocation.callRealMethod();
        }).when(policyTarget).lockAndIsBlocked(viewer, author);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 상위 TX로 감싸지 않고 실제 HTTP 진입과 같이 공개 서비스가 TX를 시작한다.
            Future<?> pending = executor.submit(() -> interact(action));
            assertThat(policyReached.await(10, TimeUnit.SECONDS)).isTrue();
            // 이 시점에 쓰기 요청은 회원 조회와 피드 잠금을 마쳤지만 pair 잠금은 아직 잡지 않았다.
            // 인자 순서가 반대인 실제 차단 요청을 커밋해, 기다리던 쓰기가 최신 차단을 읽게 한다.
            blocks.block(author, viewer);
            resumePolicy.countDown();

            assertThatThrownBy(() -> pending.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(FeedNotFoundException.class);
            assertThat(actualIsolation.get()).isEqualTo("read committed");
            assertThat(count("SELECT count(*) FROM comments WHERE feed_id = ?")).isZero();
            assertThat(count("SELECT count(*) FROM notifications WHERE feed_id = ?")).isZero();
            assertThat(count("SELECT count(*) FROM feed_likes WHERE feed_id = ?")).isEqualTo(initialLikeCount);
            assertThat(count("SELECT like_count FROM feeds WHERE id = ?")).isEqualTo(initialLikeCount);
            assertThat(count("SELECT comment_count FROM feeds WHERE id = ?")).isZero();
            if (action == Action.UNLIKE) {
                assertThat(jdbc.queryForObject("SELECT id FROM feed_likes WHERE feed_id = ?", UUID.class, feed))
                        .isEqualTo(existingLike);
            }
        } finally {
            resumePolicy.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("풀 기본격리가 RR이어도 실제 피드 게시와 푸시 예약은 RC 트랜잭션을 사용한다")
    void publicationStartsReadCommittedBeforeTheAuthorGate() {
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
        UUID diary = UUID.randomUUID();
        Long prompt = jdbc.queryForObject("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('테스트', '테스트', ?) RETURNING id
                """, Long.class, "references/push-publication/" + diary);
        prompts.add(prompt);
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '원문')",
                diary, author);
        jdbc.update("""
                INSERT INTO diary_generations
                    (id, diary_id, prompt_id, idempotency_key, request_fingerprint, status, title, image_object_key)
                VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', '게시 검증', ?)
                """, UUID.randomUUID(), diary, prompt, UUID.randomUUID(), "c".repeat(64), "generated/" + diary);
        var isolation = new AtomicReference<String>();
        FeedPushPublicationGuard target = AopTestUtils.getUltimateTargetObject(publicationGuard);
        doAnswer(invocation -> {
            isolation.set(jdbc.queryForObject("SHOW transaction_isolation", String.class));
            return invocation.callRealMethod();
        }).when(target).lockForPublication(author);
        Long category = jdbc.queryForObject("SELECT id FROM categories WHERE name = '일상'", Long.class);

        var result = publication.publish(author, diary, category);

        assertThat(isolation.get()).isEqualTo("read committed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feeds WHERE id = ?", Long.class, result.id())).isEqualTo(1);
    }

    private Object interact(Action action) {
        return switch (action) {
            case LIKE -> likes.like(viewer, feed);
            case UNLIKE -> likes.unlike(viewer, feed);
            case COMMENT -> comments.create(viewer, feed, "차단 뒤에는 저장되면 안 되는 댓글");
        };
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class, feed);
    }

    private UUID member() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, name, nickname, profile_image_code) VALUES (?, '회원', ?, 1)", id,
                "경합" + id.toString().substring(0, 8));
        jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'KAKAO', ?)",
                id, id.toString());
        users.add(id);
        return id;
    }

    enum Action { LIKE, UNLIKE, COMMENT }
}
