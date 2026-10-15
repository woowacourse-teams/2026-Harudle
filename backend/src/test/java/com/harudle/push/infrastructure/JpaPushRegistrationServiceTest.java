package com.harudle.push.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.push.infrastructure.JpaPushRegistrationService.InvalidPushSession;
import com.harudle.push.infrastructure.JpaPushRegistrationService.Registration;
import com.harudle.push.infrastructure.JpaPushRegistrationService.RegistrationConflict;
import com.harudle.push.infrastructure.JpaPushRegistrationService.StaleRegistration;
import com.harudle.push.infrastructure.JpaPushRegistrationService.StalePushState;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class JpaPushRegistrationServiceTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired private JpaPushRegistrationService registrations;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private JwtDecoder jwtDecoder;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID aliceSession = UUID.randomUUID();
    private final UUID bobSession = UUID.randomUUID();
    private final UUID device = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        update("INSERT INTO users (id, name) VALUES (?, 'A'), (?, 'B')", alice, bob);
        session(aliceSession, alice);
        session(bobSession, bob);
    }

    @AfterEach
    void tearDown() {
        update("DELETE FROM users WHERE id IN (?, ?)", alice, bob);
    }

    @Test
    @DisplayName("같은 세션·기기·FID로 PUT을 반복하면 등록 하나를 반환하고 버전을 올리지 않는다")
    void repeatedPutIsIdempotent() {
        Registration first = register(alice, aliceSession, device, "F1", null);
        Registration repeated = register(alice, aliceSession, device, "F1", null);

        assertThat(repeated).isEqualTo(first);
        assertThat(number("SELECT count(*) FROM push_registrations WHERE device_id = ?", device)).isEqualTo(1);
        assertThat(number("SELECT version FROM push_registrations WHERE id = ?", first.id())).isZero();
    }

    @Test
    @DisplayName("같은 기기의 FID를 F1에서 F2로 바꾸면 등록 ID를 유지하고 버전만 올린다")
    void changesFidWithExpectedVersion() {
        Registration first = register(alice, aliceSession, device, "F1", null);
        assertThatThrownBy(() -> registrations.register(alice, aliceSession, device, "F2",
                UUID.randomUUID(), first.version(), first.stateVersion()))
                .isInstanceOf(StaleRegistration.class);

        Registration changed = register(alice, aliceSession, device, "F2", first);
        Registration repeated = register(alice, aliceSession, device, "F2", first);

        assertThat(changed.id()).isEqualTo(first.id());
        assertThat(changed.version()).isEqualTo(1);
        assertThat(repeated).isEqualTo(changed);
        assertThat(text("SELECT recipient_value FROM push_registrations WHERE id = ?", first.id())).isEqualTo("F2");
    }

    @Test
    @DisplayName("F2가 먼저 저장되면 뒤늦은 F1의 '등록 없음' 요청은 현재 등록을 알려주고 F2를 유지한다")
    void lateCreateCannotReplaceNewerFid() {
        Registration f2 = register(alice, aliceSession, device, "F2", null);

        assertThatThrownBy(() -> register(alice, aliceSession, device, "F1", null))
                .isInstanceOfSatisfying(StaleRegistration.class, conflict ->
                        assertThat(conflict.current()).isEqualTo(f2));
        assertThat(text("SELECT recipient_value FROM push_registrations WHERE id = ?", f2.id())).isEqualTo("F2");
    }

    @Test
    @DisplayName("F1이 먼저 저장돼도 F2를 새 버전으로 재요청하면 늦은 F1이 덮어쓰지 못한다")
    void retryWithCurrentVersionPreservesNewerFid() {
        Registration f1 = register(alice, aliceSession, device, "F1", null);
        assertThatThrownBy(() -> register(alice, aliceSession, device, "F2", null))
                .isInstanceOfSatisfying(StaleRegistration.class, conflict ->
                        assertThat(conflict.current()).isEqualTo(f1));
        Registration f2 = register(alice, aliceSession, device, "F2", f1);

        assertThatThrownBy(() -> register(alice, aliceSession, device, "F1", f1))
                .isInstanceOfSatisfying(StaleRegistration.class, conflict ->
                        assertThat(conflict.current()).isEqualTo(f2));
        assertThat(text("SELECT recipient_value FROM push_registrations WHERE id = ?", f2.id())).isEqualTo("F2");
    }

    @Test
    @DisplayName("서로 다른 FID의 최초 등록이 동시에 도착해도 한 건만 저장하고 패자는 현재 버전을 받는다")
    void concurrentCreateHasOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var f1 = workers.submit(() -> concurrentRegister(start, "F1", null));
            var f2 = workers.submit(() -> concurrentRegister(start, "F2", null));
            start.countDown();

            List<Object> results = List.of(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Registration.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(this::isStale)).hasSize(1);
            Registration winner = (Registration) results.stream().filter(Registration.class::isInstance)
                    .findFirst().orElseThrow();
            assertThat(winner.stateVersion()).isEqualTo(1);
            assertThat(number("SELECT count(*) FROM push_registrations WHERE device_id = ?", device)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("같은 버전으로 FID 변경이 동시에 도착하면 한 변경만 성공하고 나머지는 현재 버전을 받는다")
    void concurrentUpdatesDoNotOverwriteWinner() throws Exception {
        Registration first = register(alice, aliceSession, device, "F1", null);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var f2 = workers.submit(() -> concurrentRegister(start, "F2", first));
            var f3 = workers.submit(() -> concurrentRegister(start, "F3", first));
            start.countDown();

            List<Object> results = List.of(f2.get(10, TimeUnit.SECONDS), f3.get(10, TimeUnit.SECONDS));
            Registration winner = (Registration) results.stream().filter(Registration.class::isInstance)
                    .findFirst().orElseThrow();
            assertThat(results.stream().filter(this::isStale)).hasSize(1);
            assertThat(winner.version()).isEqualTo(1);
            assertThat(text("SELECT recipient_value FROM push_registrations WHERE id = ?", first.id()))
                    .isIn("F2", "F3");
        }
    }

    @Test
    @DisplayName("같은 사용자라도 다른 유효 세션은 기존 기기 등록을 변경하지 못한다")
    void anotherActiveSessionCannotTakeDevice() {
        UUID secondSession = UUID.randomUUID();
        session(secondSession, alice);
        Registration first = register(alice, aliceSession, device, "F1", null);
        job(first.id(), alice, "PENDING");

        assertThatThrownBy(() -> register(alice, secondSession, device, "F2", null))
                .isInstanceOf(RegistrationConflict.class);
        assertThatThrownBy(() -> register(alice, secondSession, UUID.randomUUID(), "F1", null))
                .isInstanceOf(RegistrationConflict.class);
        assertThat(text("SELECT recipient_value FROM push_registrations WHERE id = ?", first.id())).isEqualTo("F1");
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? AND status = 'PENDING'",
                first.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 사용자의 유효한 기기 또는 FID는 409 충돌로 보호한다")
    void anotherUserCannotTakeActiveDeviceOrFid() {
        Registration first = register(alice, aliceSession, device, "F1", null);
        job(first.id(), alice, "PENDING");

        assertThatThrownBy(() -> register(bob, bobSession, device, "F2", null))
                .isInstanceOf(RegistrationConflict.class);
        assertThatThrownBy(() -> register(bob, bobSession, UUID.randomUUID(), "F1", null))
                .isInstanceOf(RegistrationConflict.class);
        assertThat(number("SELECT count(*) FROM push_registrations WHERE revoked_at IS NULL")).isEqualTo(1);
        assertThat(text("SELECT recipient_value FROM push_registrations WHERE id = ?", first.id())).isEqualTo("F1");
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? AND status = 'PENDING'",
                first.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("기존 세션이 만료됐다면 이전 기기 등록과 미발송 작업을 취소하고 새 세션에 등록한다")
    void expiredSessionCanBeReplacedAndCancelsJobs() {
        Registration old = register(alice, aliceSession, device, "F1", null);
        job(old.id(), alice, "PENDING");
        job(old.id(), alice, "PROCESSING");
        job(old.id(), alice, "SENT");
        update("UPDATE refresh_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE session_id = ?",
                aliceSession);

        Registration next = register(bob, bobSession, device, "F2", null);

        assertThat(next.id()).isNotEqualTo(old.id());
        assertThat(number("SELECT count(*) FROM push_registrations WHERE id = ? AND revoked_at IS NOT NULL", old.id()))
                .isEqualTo(1);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? AND status = 'CANCELLED'",
                old.id())).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? AND status = 'SENT'",
                old.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 사용자의 다른 세션이 유효해도 만료된 세션의 기기 등록은 교체한다")
    void anotherActiveSessionDoesNotKeepExpiredRegistrationAlive() {
        UUID newSession = UUID.randomUUID();
        session(newSession, alice);
        Registration old = register(alice, aliceSession, device, "F1", null);
        job(old.id(), alice, "PENDING");
        update("UPDATE refresh_tokens SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE session_id = ?",
                aliceSession);

        Registration next = register(alice, newSession, device, "F2", null);

        assertThat(next.id()).isNotEqualTo(old.id());
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? AND status = 'CANCELLED'",
                old.id())).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM refresh_tokens WHERE session_id = ? AND revoked_at IS NULL",
                newSession)).isEqualTo(1);
    }

    @Test
    @DisplayName("Refresh Token이 같은 세션에서 교체되면 그 세션의 푸시 등록은 계속 유효하다")
    void tokenRotationKeepsRegistrationUsable() {
        Registration first = register(alice, aliceSession, device, "F1", null);
        transactions.executeWithoutResult(status -> {
            var oldToken = entityManager.createNativeQuery("UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE session_id = ?");
            oldToken.setParameter(1, aliceSession).executeUpdate();
            var newToken = entityManager.createNativeQuery("""
                    INSERT INTO refresh_tokens (user_id, token_hash, expires_at, session_id)
                    VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day', ?)
                    """);
            newToken.setParameter(1, alice);
            newToken.setParameter(2, UUID.randomUUID().toString().replace("-", "").repeat(2));
            newToken.setParameter(3, aliceSession).executeUpdate();
        });

        Registration changed = register(alice, aliceSession, device, "F2", first);
        assertThat(changed.id()).isEqualTo(first.id());
        assertThat(changed.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 세션에서 deviceId만 바뀌고 FID가 유지되면 옛 등록을 해제하고 새 기기로 옮긴다")
    void sameSessionMayMoveFidToNewDevice() {
        Registration old = register(alice, aliceSession, device, "F1", null);
        job(old.id(), alice, "PENDING");

        Registration moved = register(alice, aliceSession, UUID.randomUUID(), "F1", null);

        assertThat(moved.id()).isNotEqualTo(old.id());
        assertThat(number("SELECT count(*) FROM push_registrations WHERE id = ? AND revoked_at IS NOT NULL", old.id()))
                .isEqualTo(1);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? AND status = 'CANCELLED'",
                old.id())).isEqualTo(1);
        assertThatThrownBy(() -> registrations.register(alice, aliceSession, device, "F1", null, null,
                old.stateVersion())).isInstanceOf(StalePushState.class);
    }

    @Test
    @DisplayName("한 세션에 서로 다른 FID의 기기를 여러 대 등록해도 각 등록은 독립적으로 남는다")
    void oneSessionCanHaveMultipleDevices() {
        Registration first = register(alice, aliceSession, device, "F1", null);
        Registration second = register(alice, aliceSession, UUID.randomUUID(), "F2", null);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(number("SELECT count(*) FROM push_registrations WHERE session_id = ? AND revoked_at IS NULL",
                aliceSession)).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 기기의 FID라도 기존 세션이 만료됐다면 옛 작업을 취소하고 새 기기에 등록한다")
    void expiredFidOwnerCanBeReplaced() {
        Registration old = register(alice, aliceSession, device, "F1", null);
        job(old.id(), alice, "PENDING");
        update("UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE session_id = ?", aliceSession);

        Registration next = register(bob, bobSession, UUID.randomUUID(), "F1", null);

        assertThat(next.id()).isNotEqualTo(old.id());
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? AND status = 'CANCELLED'",
                old.id())).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM push_registrations WHERE recipient_value = 'F1' AND revoked_at IS NULL"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("세션 연결이 없는 기존 등록은 미발송 작업과 함께 정리한 뒤 새 세션에 등록한다")
    void legacyRegistrationWithoutSessionCanBeReplaced() {
        UUID old = UUID.randomUUID();
        update("""
                INSERT INTO push_registrations (id, user_id, device_id, recipient_value)
                VALUES (?, ?, ?, 'old-fid')
                """, old, alice, device);
        job(old, alice, "PENDING");

        Registration next = register(bob, bobSession, device, "F2", null);

        assertThat(next.id()).isNotEqualTo(old);
        assertThat(number("SELECT count(*) FROM push_registrations WHERE id = ? AND revoked_at IS NOT NULL", old))
                .isEqualTo(1);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? AND status = 'CANCELLED'",
                old)).isEqualTo(1);
    }

    @Test
    @DisplayName("등록 해제는 내 세션의 기기만 해제하고 PENDING·PROCESSING을 취소한다")
    void revokeOnlyOwnDeviceAndUnsentJobs() {
        Registration old = register(alice, aliceSession, device, "F1", null);
        job(old.id(), alice, "PENDING");
        job(old.id(), alice, "PROCESSING");
        job(old.id(), alice, "SENT");
        UUID otherSession = UUID.randomUUID();
        session(otherSession, alice);
        revoke(bob, bobSession, device);
        revoke(alice, otherSession, device);
        assertThat(number("SELECT count(*) FROM push_registrations WHERE id = ? AND revoked_at IS NULL", old.id()))
                .isEqualTo(1);

        long disabledVersion = revoke(alice, aliceSession, device);
        assertThat(registrations.revoke(alice, aliceSession, device, disabledVersion - 1))
                .isEqualTo(disabledVersion);

        assertThat(number("SELECT count(*) FROM push_registrations WHERE id = ? AND revoked_at IS NOT NULL", old.id()))
                .isEqualTo(1);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? AND status = 'CANCELLED'",
                old.id())).isEqualTo(2);
        assertThat(number("""
                SELECT count(*) FROM notification_outbox
                WHERE push_registration_id = ? AND status = 'CANCELLED'
                  AND lock_token IS NULL AND locked_until IS NULL AND sent_at IS NULL
                """, old.id())).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE push_registration_id = ? AND status = 'SENT'",
                old.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("만료·해제된 세션이나 다른 사용자의 세션으로는 등록할 수 없다")
    void requiresActiveOwnedSession() {
        assertThatThrownBy(() -> register(bob, aliceSession, device, "F1", null))
                .isInstanceOf(InvalidPushSession.class);
        update("UPDATE auth_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?", aliceSession);
        assertThatThrownBy(() -> register(alice, aliceSession, device, "F1", null))
                .isInstanceOf(InvalidPushSession.class);
        update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", bob);
        assertThatThrownBy(() -> register(bob, bobSession, device, "F1", null))
                .isInstanceOf(InvalidPushSession.class);
        assertThat(number("SELECT count(*) FROM push_registrations")).isZero();
    }

    @Test
    @DisplayName("등록 ID와 버전이 짝을 이루지 않거나 FID가 비어 있으면 DB를 변경하지 않는다")
    void rejectsInvalidRegistrationRequest() {
        assertThatThrownBy(() -> registrations.register(alice, aliceSession, device, "F1",
                UUID.randomUUID(), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registrations.register(alice, aliceSession, device, " ", null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(number("SELECT count(*) FROM push_registrations")).isZero();
    }

    @Test
    @DisplayName("첫 PUT보다 DELETE가 먼저 끝나면 늦은 PUT을 거절하고 다시 켤 때만 등록한다")
    void deleteBeforeFirstPutRejectsLateRegistration() {
        long disabled = registrations.revoke(alice, aliceSession, device, 0);

        assertThat(disabled).isEqualTo(1);
        assertThatThrownBy(() -> registrations.register(alice, aliceSession, device, "F1", null, null, 0))
                .isInstanceOfSatisfying(StalePushState.class, conflict -> {
                    assertThat(conflict.stateVersion()).isEqualTo(1);
                    assertThat(conflict.enabled()).isFalse();
                });
        assertThat(number("SELECT count(*) FROM push_registrations WHERE revoked_at IS NULL")).isZero();

        Registration enabled = registrations.register(alice, aliceSession, device, "F2", null, null, disabled);
        assertThat(enabled.stateVersion()).isEqualTo(2);
        assertThat(text("SELECT recipient_value FROM push_registrations WHERE id = ?", enabled.id())).isEqualTo("F2");
    }

    @Test
    @DisplayName("첫 PUT이 먼저 끝나면 오래된 DELETE는 거절하고 현재 번호로 다시 해제한다")
    void firstPutBeforeDeleteNeedsCurrentStateVersion() {
        Registration enabled = registrations.register(alice, aliceSession, device, "F1", null, null, 0);
        job(enabled.id(), alice, "PENDING");

        assertThatThrownBy(() -> registrations.revoke(alice, aliceSession, device, 0))
                .isInstanceOfSatisfying(StalePushState.class, conflict -> {
                    assertThat(conflict.stateVersion()).isEqualTo(1);
                    assertThat(conflict.enabled()).isTrue();
                });
        assertThat(registrations.revoke(alice, aliceSession, device, enabled.stateVersion())).isEqualTo(2);
        assertThat(number("SELECT count(*) FROM notification_outbox WHERE status = 'CANCELLED'"
                + " AND push_registration_id = ?", enabled.id())).isEqualTo(1);
        assertThatThrownBy(() -> registrations.register(alice, aliceSession, device, "F1", null, null, 0))
                .isInstanceOf(StalePushState.class);
    }

    @Test
    @DisplayName("해제 요청을 반복하거나 알림을 다시 켜도 이전 DELETE가 새 등록을 지우지 못한다")
    void repeatedDeleteCannotUndoLaterRegistration() {
        long disabled = registrations.revoke(alice, aliceSession, device, 0);
        assertThat(registrations.revoke(alice, aliceSession, device, 0)).isEqualTo(disabled);
        Registration enabled = registrations.register(alice, aliceSession, device, "F1", null, null, disabled);

        assertThatThrownBy(() -> registrations.revoke(alice, aliceSession, device, 0))
                .isInstanceOf(StalePushState.class);
        assertThat(number("SELECT count(*) FROM push_registrations WHERE id = ? AND revoked_at IS NULL",
                enabled.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("첫 PUT과 DELETE가 동시에 처리돼도 한 요청만 성공하고 끄기 재요청 후 등록이 남지 않는다")
    void concurrentFirstPutAndDeleteKeepLatestChoice() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var put = workers.submit(() -> {
                start.await();
                try {
                    return registrations.register(alice, aliceSession, device, "F1", null, null, 0);
                } catch (StalePushState conflict) {
                    return conflict;
                }
            });
            var delete = workers.submit(() -> {
                start.await();
                try {
                    return registrations.revoke(alice, aliceSession, device, 0);
                } catch (StalePushState conflict) {
                    return conflict;
                }
            });
            start.countDown();

            List<Object> results = List.of(put.get(10, TimeUnit.SECONDS), delete.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(StalePushState.class::isInstance)).hasSize(1);
            assertThat(stateVersion(aliceSession, device)).isEqualTo(1);
            registrations.revoke(alice, aliceSession, device, 1);
            assertThat(number("SELECT count(*) FROM push_registrations WHERE revoked_at IS NULL")).isZero();
        }
    }

    @Test
    @DisplayName("등록을 포함한 트랜잭션이 롤백되면 기기 등록도 남지 않는다")
    void registrationRollsBackWithTransaction() {
        transactions.executeWithoutResult(status -> {
            register(alice, aliceSession, device, "F1", null);
            status.setRollbackOnly();
        });

        assertThat(number("SELECT count(*) FROM push_registrations WHERE device_id = ?", device)).isZero();
    }

    private Object concurrentRegister(CountDownLatch start, String fid, Registration expected) throws InterruptedException {
        start.await();
        try {
            return register(alice, aliceSession, device, fid, expected);
        } catch (StaleRegistration | StalePushState conflict) {
            return conflict;
        }
    }

    private boolean isStale(Object result) {
        return result instanceof StaleRegistration || result instanceof StalePushState;
    }

    private Registration register(UUID user, UUID session, UUID deviceId, String fid, Registration expected) {
        long stateVersion = stateVersion(session, deviceId);
        if (expected == null) {
            return registrations.register(user, session, deviceId, fid, null, null, stateVersion);
        }
        return registrations.register(user, session, deviceId, fid, expected.id(), expected.version(), stateVersion);
    }

    private long revoke(UUID user, UUID session, UUID deviceId) {
        return registrations.revoke(user, session, deviceId, stateVersion(session, deviceId));
    }

    private long stateVersion(UUID session, UUID deviceId) {
        return transactions.execute(status -> {
            List<?> rows = entityManager.createNativeQuery("""
                    SELECT state_version FROM push_registration_states
                    WHERE session_id = ? AND device_id = ?
                    """).setParameter(1, session).setParameter(2, deviceId).getResultList();
            if (rows.isEmpty()) {
                return 0L;
            }
            return ((Number) rows.getFirst()).longValue();
        });
    }

    private void session(UUID id, UUID user) {
        update("INSERT INTO auth_sessions (id, user_id) VALUES (?, ?)", id, user);
        update("""
                INSERT INTO refresh_tokens (user_id, token_hash, expires_at, session_id)
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day', ?)
                """, user, UUID.randomUUID().toString().replace("-", "").repeat(2), id);
    }

    private void job(UUID registration, UUID recipient, String status) {
        UUID diary = UUID.randomUUID();
        UUID feed = UUID.randomUUID();
        update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '일기')",
                diary, alice);
        update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, (SELECT id FROM categories WHERE name = '일상'))",
                feed, diary);
        update("""
                INSERT INTO notification_outbox
                    (id, event_id, feed_id, recipient_user_id, push_registration_id, status,
                     lock_token, locked_until, sent_at)
                VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? = 'PROCESSING' THEN gen_random_uuid() END,
                        CASE WHEN ? = 'PROCESSING' THEN CURRENT_TIMESTAMP + INTERVAL '1 minute' END,
                        CASE WHEN ? = 'SENT' THEN CURRENT_TIMESTAMP END)
                """, UUID.randomUUID(), UUID.randomUUID(), feed, recipient, registration,
                status, status, status, status);
    }

    private void update(String sql, Object... values) {
        transactions.executeWithoutResult(status -> {
            var query = entityManager.createNativeQuery(sql);
            for (int i = 0; i < values.length; i++) query.setParameter(i + 1, values[i]);
            query.executeUpdate();
        });
    }

    private long number(String sql, Object... values) {
        return ((Number) value(sql, values)).longValue();
    }

    private String text(String sql, Object... values) {
        return (String) value(sql, values);
    }

    private Object value(String sql, Object... values) {
        return transactions.execute(status -> {
            var query = entityManager.createNativeQuery(sql);
            for (int i = 0; i < values.length; i++) query.setParameter(i + 1, values[i]);
            return query.getSingleResult();
        });
    }
}
