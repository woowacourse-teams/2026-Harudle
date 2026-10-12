package com.harudle.feed.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.feed.repository.FeedLikeRepository;
import com.harudle.feed.service.FeedLikeService;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedInteractionPort;
import com.harudle.feed.service.port.FeedInteractionPort.CounterChange;
import com.harudle.feed.service.port.FeedInteractionPort.CounterKind;
import com.harudle.feed.service.port.FeedLikeReader;
import com.harudle.notification.service.port.InAppNotificationRecorder;
import com.harudle.notification.service.port.InAppNotificationRecorder.Interaction;
import com.harudle.notification.service.port.InAppNotificationRecorder.Result;
import com.harudle.notification.service.port.InAppNotificationRecorder.Type;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "harudle.generation.adapters.enabled=false")
@AutoConfigureMockMvc
class FeedLikePersistenceTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-10-12T03:00:00Z");
    private static final int WORKER_COUNT = 8;
    private static final String CSRF_TOKEN = "real-cookie-and-header-value";

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired
    private FeedLikeService service;

    @Autowired
    private FeedInteractionPort interactions;

    @Autowired
    private FeedLikeReader reader;

    @Autowired
    private FeedLikeRepository likes;

    @Autowired
    private InAppNotificationRecorder notifications;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final List<UUID> fixtureUsers = new ArrayList<>();
    private UUID authorId;
    private UUID viewerId;
    private UUID diaryId;
    private UUID feedId;

    @BeforeEach
    void setUp() {
        authorId = createMember();
        viewerId = createMember();
        diaryId = createDiary(authorId);
        feedId = createFeed(diaryId);
        when(jwtDecoder.decode("member-token")).thenReturn(jwt("member-token", viewerId));
    }

    @AfterEach
    void tearDown() {
        // Every row created by this test belongs to one of these users. Foreign
        // keys clean up only our fixtures, not other tests' rows or seed data.
        for (UUID userId : fixtureUsers) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        fixtureUsers.clear();
    }

    @Test
    @DisplayName("실제 보안 체인과 서비스 및 DB를 거친 HTTP 반복 좋아요·취소가 한 번만 반영된다")
    void persistLikeAndUnlikeThroughFullHttpStack() throws Exception {
        for (int repeat = 0; repeat < 2; repeat++) {
            mockMvc.perform(withCsrf(bearer(put(likePath()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.likedByMe").value(true))
                    .andExpect(jsonPath("$.likeCount").value(1));
            assertCounts(1, 0);
            assertThat(notificationCount()).isEqualTo(1);
        }
        for (int repeat = 0; repeat < 2; repeat++) {
            mockMvc.perform(withCsrf(bearer(delete(likePath()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.likedByMe").value(false))
                    .andExpect(jsonPath("$.likeCount").value(0));
            assertCounts(0, 0);
            assertThat(notificationCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("실제 HTTP에서 인증 또는 CSRF가 없으면 반응과 개수 및 알림을 변경하지 않는다")
    void rejectUnauthenticatedAndMissingCsrfRequestsWithoutWrites() throws Exception {
        mockMvc.perform(bearer(put(likePath())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        mockMvc.perform(put(likePath()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(withCsrf(delete(likePath())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertCounts(0, 0);
        assertThat(notificationCount()).isZero();
    }

    @Test
    @DisplayName("JWT와 CSRF가 유효해도 OAuth 회원이 아닌 사용자에게 실제 HTTP 좋아요를 허용하지 않는다")
    void rejectGuestJwtThroughServiceMembershipCheck() throws Exception {
        UUID guestId = createUser();
        when(jwtDecoder.decode("guest-token")).thenReturn(jwt("guest-token", guestId));

        mockMvc.perform(withCsrf(put(likePath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer guest-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertCounts(0, 0);
        assertThat(notificationCount()).isZero();
    }

    @Test
    @DisplayName("반복 좋아요·취소는 개수를 유지하고 재좋아요는 새 행동과 알림을 만든다")
    void persistIdempotentLikeUnlikeAndNewRelike() {
        assertThat(service.like(viewerId, feedId).likedByMe()).isTrue();
        UUID firstLikeId = onlyLikeId();
        assertThat(service.like(viewerId, feedId).likeCount()).isEqualTo(1);
        assertCounts(1, 0);
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(uuidValue("SELECT source_action_id FROM notifications WHERE feed_id = ?", feedId))
                .isEqualTo(firstLikeId);

        var unliked = service.unlike(viewerId, feedId);
        assertThat(unliked.likedByMe()).isFalse();
        assertThat(unliked.likeCount()).isZero();
        assertThat(service.unlike(viewerId, feedId).likeCount()).isZero();
        assertCounts(0, 0);
        assertThat(notificationCount()).isEqualTo(1);

        assertThat(service.like(viewerId, feedId).likeCount()).isEqualTo(1);
        UUID newLikeId = onlyLikeId();
        assertThat(newLikeId).isNotEqualTo(firstLikeId);
        assertThat(notificationCount()).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT source_action_id FROM notifications WHERE feed_id = ?", UUID.class, feedId))
                .containsExactlyInAnyOrder(firstLikeId, newLikeId);
    }

    @Test
    @DisplayName("자기 피드 좋아요도 저장하되 자기 알림은 생성하지 않는다")
    void persistSelfLikeWithoutNotification() {
        var result = service.like(authorId, feedId);

        assertThat(result.likedByMe()).isTrue();
        assertThat(result.likeCount()).isEqualTo(1);
        assertCounts(1, 0);
        assertThat(notificationCount()).isZero();
        assertThat(reader.findLikedFeedIds(authorId, Set.of(feedId))).containsExactly(feedId);
    }

    @Test
    @DisplayName("동일 회원의 동시 PUT은 좋아요·개수 증가·알림을 한 번만 만든다")
    void serializeConcurrentLikesFromSameMember() throws Exception {
        List<Callable<Boolean>> tasks = IntStream.range(0, WORKER_COUNT)
                .mapToObj(index -> (Callable<Boolean>) () -> service.like(viewerId, feedId).likedByMe())
                .toList();

        assertThat(runConcurrently(tasks)).containsOnly(true).hasSize(WORKER_COUNT);
        assertCounts(1, 0);
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("여러 회원의 동시 좋아요는 개수 증가를 유실하지 않는다")
    void serializeConcurrentLikesFromDifferentMembers() throws Exception {
        List<UUID> members = IntStream.range(0, WORKER_COUNT).mapToObj(index -> createMember()).toList();
        List<Callable<Integer>> tasks = members.stream()
                .map(member -> (Callable<Integer>) () -> service.like(member, feedId).likeCount())
                .toList();

        assertThat(runConcurrently(tasks)).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
        assertCounts(WORKER_COUNT, 0);
        assertThat(notificationCount()).isEqualTo(WORKER_COUNT);
    }

    @Test
    @DisplayName("동시 좋아요·취소의 순서와 무관하게 저장 행과 개수가 같고 음수가 되지 않는다")
    void keepCountsConsistentUnderMixedRequests() throws Exception {
        List<Callable<Integer>> tasks = IntStream.range(0, WORKER_COUNT)
                .mapToObj(index -> (Callable<Integer>) () -> index % 2 == 0
                        ? service.like(viewerId, feedId).likeCount()
                        : service.unlike(viewerId, feedId).likeCount())
                .toList();

        assertThat(runConcurrently(tasks)).allMatch(count -> count >= 0 && count <= 1);
        int persistedLikes = intValue("SELECT count(*) FROM feed_likes WHERE feed_id = ?", feedId);
        assertCounts(persistedLikes, 0);
        assertThat(notificationCount()).isBetween(1, WORKER_COUNT / 2);
    }

    @Test
    @DisplayName("동시 반복 취소는 실제 삭제와 개수 감소를 한 번만 수행한다")
    void serializeConcurrentUnlikes() throws Exception {
        service.like(viewerId, feedId);
        List<Callable<Boolean>> tasks = IntStream.range(0, WORKER_COUNT)
                .mapToObj(index -> (Callable<Boolean>) () -> service.unlike(viewerId, feedId).likedByMe())
                .toList();

        assertThat(runConcurrently(tasks)).containsOnly(false);
        assertCounts(0, 0);
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("알림 INSERT 실패는 이미 추가한 좋아요와 개수 증가까지 모두 롤백한다")
    void rollbackLikeAndCounterWhenNotificationFails() {
        String suffix = feedId.toString().replace("-", "");
        String function = "test_fail_like_notification_" + suffix;
        String trigger = "test_fail_like_notification_" + suffix;
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS "
                + "'BEGIN RAISE EXCEPTION ''test notification failure'' USING ERRCODE = ''23514''; END'");
        try {
            jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON notifications FOR EACH ROW "
                    + "WHEN (NEW.feed_id = '" + feedId + "'::uuid) EXECUTE FUNCTION " + function + "()");

            assertThatThrownBy(() -> service.like(viewerId, feedId)).isInstanceOf(RuntimeException.class);
            assertCounts(0, 0);
            assertThat(notificationCount()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON notifications");
            jdbc.execute("DROP FUNCTION " + function + "()");
        }

        assertThat(service.like(viewerId, feedId).likeCount()).isEqualTo(1);
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("개수 불일치로 감소할 수 없으면 좋아요 삭제와 알림 이력도 원상태를 유지한다")
    void rollbackUnlikeWhenCounterWouldUnderflow() {
        service.like(viewerId, feedId);
        UUID originalLikeId = onlyLikeId();
        jdbc.update("UPDATE feeds SET like_count = 0 WHERE id = ?", feedId);

        assertThatThrownBy(() -> service.unlike(viewerId, feedId))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(onlyLikeId()).isEqualTo(originalLikeId);
        assertThat(intValue("SELECT like_count FROM feeds WHERE id = ?", feedId)).isZero();
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("알림 중복은 오류가 아니며 같은 트랜잭션에서 다른 변경을 이어서 커밋할 수 있다")
    void duplicateNotificationDoesNotAbortTransaction() {
        UUID actionId = UUID.randomUUID();
        Interaction event = event(actionId, Type.COMMENT, viewerId, authorId);

        transactions.executeWithoutResult(status -> {
            interactions.lockActive(feedId);
            assertThat(notifications.record(event)).isEqualTo(Result.CREATED);
            assertThat(notifications.record(event)).isEqualTo(Result.DUPLICATE);
            assertThat(interactions.adjustCounter(feedId, CounterKind.COMMENT, CounterChange.ADDED))
                    .isEqualTo(new FeedInteractionPort.Counts(0, 1));
        });

        assertThat(notificationCount()).isEqualTo(1);
        assertThat(intValue("SELECT comment_count FROM feeds WHERE id = ?", feedId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 행동 ID라도 수신자 또는 종류가 다르면 별도 알림으로 저장한다")
    void deduplicateNotificationByRecipientTypeAndSourceAction() {
        UUID actionId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            assertThat(notifications.record(event(actionId, Type.LIKE, viewerId, authorId)))
                    .isEqualTo(Result.CREATED);
            assertThat(notifications.record(event(actionId, Type.COMMENT, viewerId, authorId)))
                    .isEqualTo(Result.CREATED);
            assertThat(notifications.record(event(actionId, Type.LIKE, authorId, viewerId)))
                    .isEqualTo(Result.CREATED);
        });

        assertThat(notificationCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("자기 알림 기록은 오류 없이 건너뛰고 트랜잭션의 다른 작업은 커밋한다")
    void selfNotificationDoesNotAbortTransaction() {
        transactions.executeWithoutResult(status -> {
            interactions.lockActive(feedId);
            assertThat(notifications.record(event(UUID.randomUUID(), Type.LIKE, authorId, authorId)))
                    .isEqualTo(Result.SELF_SKIPPED);
            interactions.adjustCounter(feedId, CounterKind.COMMENT, CounterChange.ADDED);
        });

        assertThat(notificationCount()).isZero();
        assertThat(intValue("SELECT comment_count FROM feeds WHERE id = ?", feedId)).isEqualTo(1);
    }

    @Test
    @DisplayName("좋아요 조회는 요청 범위·조회 회원·피드 및 원본 일기 삭제 상태를 모두 반영한다")
    void readOnlyRequestedVisibleLikesForViewer() {
        UUID deletedFeed = createFeed(createDiary(authorId));
        UUID deletedDiary = createDiary(authorId);
        UUID diaryDeletedFeed = createFeed(deletedDiary);
        UUID otherMemberFeed = createFeed(createDiary(authorId));
        UUID omittedFeed = createFeed(createDiary(authorId));
        service.like(viewerId, feedId);
        service.like(viewerId, deletedFeed);
        service.like(viewerId, diaryDeletedFeed);
        service.like(authorId, otherMemberFeed);
        service.like(viewerId, omittedFeed);
        jdbc.update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedFeed);
        jdbc.update("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedDiary);

        assertThat(reader.findLikedFeedIds(viewerId,
                Set.of(feedId, deletedFeed, diaryDeletedFeed, otherMemberFeed, UUID.randomUUID())))
                .containsExactly(feedId);
        assertThat(reader.findLikedFeedIds(authorId, Set.of(feedId, otherMemberFeed)))
                .containsExactly(otherMemberFeed);
        assertThat(reader.findLikedFeedIds(viewerId, Set.of())).isEmpty();
        assertThat(reader.findLikedFeedIds(UUID.randomUUID(), Set.of(feedId))).isEmpty();
    }

    @Test
    @DisplayName("피드 또는 원본 일기가 삭제됐으면 반응 대상 잠금에 실패한다")
    void rejectDeletedParentTargets() {
        jdbc.update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", feedId);
        assertThatThrownBy(() -> service.like(viewerId, feedId)).isInstanceOf(FeedNotFoundException.class);
        assertThatThrownBy(() -> service.unlike(viewerId, feedId)).isInstanceOf(FeedNotFoundException.class);

        UUID anotherDiary = createDiary(authorId);
        UUID anotherFeed = createFeed(anotherDiary);
        jdbc.update("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", anotherDiary);
        assertThatThrownBy(() -> service.like(viewerId, anotherFeed)).isInstanceOf(FeedNotFoundException.class);
        assertThatThrownBy(() -> service.unlike(viewerId, anotherFeed)).isInstanceOf(FeedNotFoundException.class);
        assertCounts(0, 0);
        assertThat(notificationCount()).isZero();
    }

    @Test
    @DisplayName("피드 삭제가 먼저 잠금을 잡으면 기다리던 좋아요는 삭제 커밋 후 실패한다")
    void rejectLikeWaitingForFeedDeletion() throws Exception {
        assertDeletionWins(false);
    }

    @Test
    @DisplayName("일기→피드 순서로 삭제 잠금을 잡으면 기다리던 좋아요는 삭제 커밋 후 실패한다")
    void rejectLikeWaitingForDiaryAndFeedDeletion() throws Exception {
        assertDeletionWins(true);
    }

    @Test
    @DisplayName("좋아요의 피드 잠금은 커밋까지 유지되어 뒤따르는 피드 삭제와 직렬화한다")
    void serializeDeletionAfterLikeTransaction() throws Exception {
        CountDownLatch liked = new CountDownLatch(1);
        CountDownLatch releaseLike = new CountDownLatch(1);
        AtomicInteger deletionPid = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> like = executor.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(service.like(viewerId, feedId).likeCount()).isEqualTo(1);
                liked.countDown();
                await(releaseLike);
            }));
            try {
                assertThat(liked.await(5, TimeUnit.SECONDS)).isTrue();
                Future<?> deletion = executor.submit(() -> transactions.executeWithoutResult(status -> {
                    deletionPid.set(intValue("SELECT pg_backend_pid()"));
                    jdbc.update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", feedId);
                }));
                awaitBlockedBackend(deletionPid);
                releaseLike.countDown();
                like.get(10, TimeUnit.SECONDS);
                deletion.get(10, TimeUnit.SECONDS);
                assertCounts(1, 0);
                assertThat(notificationCount()).isEqualTo(1);
                assertThat(reader.findLikedFeedIds(viewerId, Set.of(feedId))).isEmpty();
            } finally {
                releaseLike.countDown();
            }
        }
    }

    @Test
    @DisplayName("피드 반응은 원본 일기를 잠그지 않아 일기→피드 삭제 잠금과 역순 교착하지 않는다")
    void doNotLockDiaryWhileLiking() throws Exception {
        CountDownLatch diaryLocked = new CountDownLatch(1);
        CountDownLatch releaseDiary = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> diaryLocker = executor.submit(() -> transactions.executeWithoutResult(status -> {
                uuidValue("SELECT id FROM diaries WHERE id = ? FOR UPDATE", diaryId);
                diaryLocked.countDown();
                await(releaseDiary);
            }));
            try {
                assertThat(diaryLocked.await(5, TimeUnit.SECONDS)).isTrue();
                Future<Integer> like = executor.submit(() -> service.like(viewerId, feedId).likeCount());
                assertThat(like.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                assertCounts(1, 0);
            } finally {
                releaseDiary.countDown();
            }
            diaryLocker.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("피드 잠금은 실제 작성자와 두 개수를 반환하고 COMMENT 조정은 좋아요 수를 보존한다")
    void lockAndAdjustIndependentCounters() {
        service.like(viewerId, feedId);

        transactions.executeWithoutResult(status -> {
            var target = interactions.lockActive(feedId);
            assertThat(target.feedId()).isEqualTo(feedId);
            assertThat(target.authorId()).isEqualTo(authorId);
            assertThat(target.counts()).isEqualTo(new FeedInteractionPort.Counts(1, 0));
            assertThat(interactions.adjustCounter(feedId, CounterKind.COMMENT, CounterChange.ADDED))
                    .isEqualTo(new FeedInteractionPort.Counts(1, 1));
            assertThat(interactions.adjustCounter(feedId, CounterKind.COMMENT, CounterChange.REMOVED))
                    .isEqualTo(new FeedInteractionPort.Counts(1, 0));
        });

        assertCounts(1, 0);
    }

    @Test
    @DisplayName("반응 변경과 알림 기록은 외부 트랜잭션 없이 독립적으로 커밋할 수 없다")
    void requireExistingTransactionForMutationPorts() {
        List<Runnable> mutations = List.of(
                () -> interactions.lockActive(feedId),
                () -> interactions.adjustCounter(feedId, CounterKind.LIKE, CounterChange.ADDED),
                () -> likes.add(UUID.randomUUID(), feedId, viewerId, OCCURRED_AT),
                () -> likes.remove(feedId, viewerId),
                () -> notifications.record(event(UUID.randomUUID(), Type.LIKE, viewerId, authorId))
        );

        for (Runnable mutation : mutations) {
            assertThatThrownBy(mutation::run).isInstanceOf(IllegalTransactionStateException.class);
        }
        assertCounts(0, 0);
        assertThat(notificationCount()).isZero();
    }

    @Test
    @DisplayName("포트는 null 입력과 알림의 null 필드를 거절하고 어떠한 행도 기록하지 않는다")
    void rejectNullInputsWithoutPersistingChanges() {
        List<Runnable> invalidMutations = List.of(
                () -> interactions.lockActive(null),
                () -> interactions.adjustCounter(null, CounterKind.LIKE, CounterChange.ADDED),
                () -> interactions.adjustCounter(feedId, null, CounterChange.ADDED),
                () -> interactions.adjustCounter(feedId, CounterKind.LIKE, null),
                () -> likes.add(null, feedId, viewerId, OCCURRED_AT),
                () -> likes.add(UUID.randomUUID(), null, viewerId, OCCURRED_AT),
                () -> likes.add(UUID.randomUUID(), feedId, null, OCCURRED_AT),
                () -> likes.add(UUID.randomUUID(), feedId, viewerId, null),
                () -> likes.remove(null, viewerId),
                () -> likes.remove(feedId, null),
                () -> notifications.record(null),
                () -> notifications.record(new Interaction(null, Type.LIKE, feedId,
                        viewerId, authorId, OCCURRED_AT)),
                () -> notifications.record(new Interaction(UUID.randomUUID(), null, feedId,
                        viewerId, authorId, OCCURRED_AT)),
                () -> notifications.record(new Interaction(UUID.randomUUID(), Type.LIKE, null,
                        viewerId, authorId, OCCURRED_AT)),
                () -> notifications.record(new Interaction(UUID.randomUUID(), Type.LIKE, feedId,
                        null, authorId, OCCURRED_AT)),
                () -> notifications.record(new Interaction(UUID.randomUUID(), Type.LIKE, feedId,
                        viewerId, null, OCCURRED_AT)),
                () -> notifications.record(new Interaction(UUID.randomUUID(), Type.LIKE, feedId,
                        viewerId, authorId, null))
        );
        for (Runnable invalid : invalidMutations) {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                interactions.lockActive(feedId);
                invalid.run();
            }))
                    .isInstanceOf(NullPointerException.class);
        }
        assertThatThrownBy(() -> reader.findLikedFeedIds(null, Set.of(feedId)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reader.findLikedFeedIds(viewerId, null))
                .isInstanceOf(NullPointerException.class);
        Set<UUID> idsWithNull = new HashSet<>();
        idsWithNull.add(feedId);
        idsWithNull.add(null);
        assertThatThrownBy(() -> reader.findLikedFeedIds(viewerId, idsWithNull))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> likes.isActiveMember(null)).isInstanceOf(NullPointerException.class);
        assertCounts(0, 0);
        assertThat(notificationCount()).isZero();
    }

    @Test
    @DisplayName("회원 판별은 활성 사용자와 OAuth 계정을 함께 확인한다")
    void requireActiveUserAndOAuthAccount() {
        UUID guestId = createUser();
        assertThat(likes.isActiveMember(viewerId)).isTrue();
        assertThat(likes.isActiveMember(guestId)).isFalse();
        assertThat(likes.isActiveMember(UUID.randomUUID())).isFalse();
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", viewerId);
        assertThat(likes.isActiveMember(viewerId)).isFalse();
    }

    private void assertDeletionWins(boolean deleteDiary) throws Exception {
        CountDownLatch deletionReady = new CountDownLatch(1);
        CountDownLatch releaseDeletion = new CountDownLatch(1);
        AtomicInteger likePid = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> deletion = executor.submit(() -> transactions.executeWithoutResult(status -> {
                if (deleteDiary) {
                    uuidValue("SELECT id FROM diaries WHERE id = ? FOR UPDATE", diaryId);
                    jdbc.update("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", diaryId);
                }
                uuidValue("SELECT id FROM feeds WHERE id = ? FOR UPDATE", feedId);
                jdbc.update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", feedId);
                deletionReady.countDown();
                await(releaseDeletion);
            }));
            try {
                assertThat(deletionReady.await(5, TimeUnit.SECONDS)).isTrue();
                Future<Throwable> like = executor.submit(() -> catchThrowable(() ->
                        transactions.executeWithoutResult(status -> {
                            likePid.set(intValue("SELECT pg_backend_pid()"));
                            service.like(viewerId, feedId);
                        })));
                awaitBlockedBackend(likePid);
                releaseDeletion.countDown();
                deletion.get(10, TimeUnit.SECONDS);
                assertThat(like.get(10, TimeUnit.SECONDS)).isInstanceOf(FeedNotFoundException.class);
                assertCounts(0, 0);
                assertThat(notificationCount()).isZero();
            } finally {
                releaseDeletion.countDown();
            }
        }
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = tasks.stream().map(task -> executor.submit(() -> {
                ready.countDown();
                await(start);
                return task.call();
            })).toList();
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                List<T> results = new ArrayList<>();
                for (Future<T> future : futures) {
                    results.add(future.get(15, TimeUnit.SECONDS));
                }
                return results;
            } finally {
                start.countDown();
            }
        }
    }

    private void awaitBlockedBackend(AtomicInteger pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (pid.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE pid = ? AND wait_event_type = 'Lock'
                    )
                    """, Boolean.class, pid.get()))) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("다른 트랜잭션의 피드 잠금에 대한 실제 대기를 확인하지 못했습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("동시 트랜잭션 조정 신호를 기다리지 못했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("동시 트랜잭션 대기가 중단됐습니다.", exception);
        }
    }

    private UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, name) VALUES (?, ?)", id, "통합테스트회원");
        fixtureUsers.add(id);
        return id;
    }

    private UUID createMember() {
        UUID id = createUser();
        jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'KAKAO', ?)",
                id, id.toString());
        return id;
    }

    private UUID createDiary(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, DATE '2026-10-12', ?)",
                id, ownerId, "피드 좋아요 통합 테스트 일기");
        return id;
    }

    private UUID createFeed(UUID sourceDiaryId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, "
                + "(SELECT id FROM categories WHERE name = '일상'))", id, sourceDiaryId);
        return id;
    }

    private Interaction event(UUID actionId, Type type, UUID actor, UUID recipient) {
        return new Interaction(actionId, type, feedId, actor, recipient, OCCURRED_AT);
    }

    private String likePath() {
        return "/api/v1/feeds/" + feedId + "/like";
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer member-token");
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN)).header("X-XSRF-TOKEN", CSRF_TOKEN);
    }

    private static Jwt jwt(String token, UUID userId) {
        return Jwt.withTokenValue(token).header("alg", "HS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    private UUID onlyLikeId() {
        return uuidValue("SELECT id FROM feed_likes WHERE feed_id = ? AND user_id = ?", feedId, viewerId);
    }

    private int notificationCount() {
        return intValue("SELECT count(*) FROM notifications WHERE feed_id = ?", feedId);
    }

    private void assertCounts(int expectedLikes, int expectedComments) {
        assertThat(intValue("SELECT count(*) FROM feed_likes WHERE feed_id = ?", feedId)).isEqualTo(expectedLikes);
        assertThat(intValue("SELECT like_count FROM feeds WHERE id = ?", feedId)).isEqualTo(expectedLikes);
        assertThat(intValue("SELECT comment_count FROM feeds WHERE id = ?", feedId)).isEqualTo(expectedComments);
    }

    private int intValue(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        assertThat(value).isNotNull();
        return value;
    }

    private UUID uuidValue(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, UUID.class, arguments);
    }
}
