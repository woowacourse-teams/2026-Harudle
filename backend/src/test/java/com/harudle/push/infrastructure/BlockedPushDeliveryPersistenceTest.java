package com.harudle.push.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.block.service.BlockService;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.push.service.port.FeedPushOutbox;
import com.harudle.support.BlockDatabaseTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "harudle.generation.adapters.enabled=false",
        "harudle.push.fcm.enabled=false"
})
class BlockedPushDeliveryPersistenceTest extends BlockDatabaseTestSupport {
    @Autowired private FeedPushOutbox outbox;
    @Autowired private BlockService blocks;
    @Autowired private JdbcPushDeliveryQueue queue;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean(enforceOverride = true) private DiaryGenerationWorker generationWorker;

    private final UUID author = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final UUID unrelated = UUID.randomUUID();
    private final UUID diary = UUID.randomUUID();
    private final UUID feed = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO users (id, name) VALUES (?, '작성자'), (?, '수신자'), (?, '다른 수신자')",
                author, recipient, unrelated);
        for (UUID member : List.of(author, recipient)) {
            jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'KAKAO', ?)",
                    member, member.toString());
        }
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '푸시 검증')",
                diary, author);
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, "
                + "(SELECT id FROM categories WHERE name = '일상'))", feed, diary);
        register(recipient);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?)", author, recipient, unrelated);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void doesNotReservePushInEitherBlockDirectionButKeepsUnrelatedRecipient(boolean reverse) {
        register(unrelated);
        block(reverse);
        var event = event();

        int created = transactions.execute(status -> outbox.enqueuePublished(event));

        assertThat(created).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE event_id = ? AND recipient_user_id = ?",
                event.eventId(), recipient)).isZero();
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE event_id = ? AND recipient_user_id = ?",
                event.eventId(), unrelated)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rechecksBothBlockDirectionsAfterClaimAndCancelsWithoutSending(boolean reverse) {
        transactions.executeWithoutResult(status -> outbox.enqueuePublished(event()));
        Instant now = Instant.now();
        var claim = queue.claim(now).orElseThrow();
        assertThat(queue.target(claim, now)).isPresent();

        block(reverse);

        assertThat(queue.target(claim, now)).isEmpty();
        assertThat(queue.cancel(claim, now)).isTrue();
        assertThat(queue.sent(claim, now)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id = ?",
                String.class, claim.id())).isEqualTo("CANCELLED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"diary", "author"})
    void skipsDeliveryWhenDiaryOrAuthorWasDeletedAfterReservation(String deletedTarget) {
        transactions.executeWithoutResult(status -> outbox.enqueuePublished(event()));
        Instant now = Instant.now();
        var claim = queue.claim(now).orElseThrow();
        if ("diary".equals(deletedTarget)) {
            jdbc.update("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", diary);
        } else {
            jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", author);
        }

        assertThat(queue.target(claim, now)).isEmpty();
    }

    @Test
    void activeUnblockedRecipientStillReceivesCurrentDeviceTarget() {
        transactions.executeWithoutResult(status -> outbox.enqueuePublished(event()));
        Instant now = Instant.now();
        var claim = queue.claim(now).orElseThrow();

        assertThat(queue.target(claim, now)).contains(new JdbcPushDeliveryQueue.Target("block-test-fid-" + recipient, 0));
    }

    @Test
    void waitsForUncommittedPublicationThenPermanentlyCancelsIt() throws Exception {
        var inserted = new CountDownLatch(1);
        var releasePublication = new CountDownLatch(1);
        var blockPid = new AtomicInteger();
        var event = event();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var publication = executor.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(outbox.enqueuePublished(event)).isEqualTo(1);
                inserted.countDown();
                await(releasePublication);
            }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            var blocking = executor.submit(() -> transactions.executeWithoutResult(status -> {
                blockPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                blocks.block(author, recipient);
            }));
            awaitAdvisoryWait(blockPid);
            assertThat(blocking.isDone()).isFalse();
            releasePublication.countDown();
            publication.get(10, TimeUnit.SECONDS);
            blocking.get(10, TimeUnit.SECONDS);

            assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE event_id = ?",
                    String.class, event.eventId())).isEqualTo("CANCELLED");
            blocks.unblock(author, recipient);
            assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE event_id = ?",
                    String.class, event.eventId())).isEqualTo("CANCELLED");
            assertThat(queue.claim(Instant.now())).isEmpty();
        } finally {
            releasePublication.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void publicationWaitsForUncommittedBlockAndRechecksInANewStatement() throws Exception {
        var blocked = new CountDownLatch(1);
        var releaseBlock = new CountDownLatch(1);
        var publicationPid = new AtomicInteger();
        var event = event();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var blocking = executor.submit(() -> transactions.executeWithoutResult(status -> {
                blocks.block(recipient, author);
                blocked.countDown();
                await(releaseBlock);
            }));
            assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
            var publication = executor.submit(() -> transactions.execute(status -> {
                publicationPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                return outbox.enqueuePublished(event);
            }));
            awaitAdvisoryWait(publicationPid);
            assertThat(publication.isDone()).isFalse();
            releaseBlock.countDown();
            blocking.get(10, TimeUnit.SECONDS);
            assertThat(publication.get(10, TimeUnit.SECONDS)).isZero();
            assertThat(number("SELECT count(*) FROM notification_outbox WHERE event_id = ?", event.eventId())).isZero();
        } finally {
            releaseBlock.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void publicationsOfSameAuthorCanShareTheGate() throws Exception {
        var inserted = new CountDownLatch(1);
        var releasePublication = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(outbox.enqueuePublished(event())).isEqualTo(1);
                inserted.countDown();
                await(releasePublication);
            }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> transactions.execute(status -> outbox.enqueuePublished(event())));
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            releasePublication.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            releasePublication.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitAdvisoryWait(AtomicInteger pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE pid = ? AND wait_event_type = 'Lock' AND wait_event = 'advisory'", Long.class, pid.get());
            if (Long.valueOf(1).equals(waiting)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("푸시 게시와 차단의 잠금 대기를 확인하지 못했습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("동시 푸시 처리 재개 신호를 기다리지 못했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("푸시 동시성 검증이 중단됐습니다.", exception);
        }
    }

    private void block(boolean reverse) {
        jdbc.update("INSERT INTO user_blocks (id, blocker_id, blocked_id, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), reverse ? recipient : author, reverse ? author : recipient);
    }

    private void register(UUID userId) {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_sessions (id, user_id) VALUES (?, ?)", session, userId);
        jdbc.update("INSERT INTO refresh_tokens (user_id, token_hash, expires_at, session_id) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day', ?)",
                userId, UUID.randomUUID().toString().replace("-", "").repeat(2), session);
        jdbc.update("INSERT INTO push_registrations (id, user_id, device_id, recipient_value, session_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, UUID.randomUUID(), "block-test-fid-" + userId, session);
    }

    private FeedPushOutbox.FeedPublished event() {
        return new FeedPushOutbox.FeedPublished(UUID.randomUUID(), feed, Instant.now());
    }

    private long number(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
