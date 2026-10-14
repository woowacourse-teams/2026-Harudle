package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.harudle.TestcontainersConfiguration;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.domain.exception.NicknameAlreadyInUseException;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.nickname.NicknameCandidateRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, NicknameChangeService.class, NicknameCandidateRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NicknameChangeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private static final String FIRST = "신나는 수달";
    private static final String SECOND = "행복한 고양이";
    private static final String THIRD = "졸린 토끼";

    @Autowired
    private NicknameChangeService service;

    @Autowired
    private UserRepository users;

    @Autowired
    private OAuthAccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM users");
    }

    @Test
    @DisplayName("후보 닉네임과 직접 입력한 닉네임으로 변경하고 기존 후보를 해제한다")
    void changesNicknameAndTransfersCandidateOwnership() {
        User user = saveUser(FIRST);

        service.change(user.getId(), SECOND);
        assertThat(owner(FIRST)).isNull();
        assertThat(owner(SECOND)).isEqualTo(user.getId());

        service.change(user.getId(), "초록");
        User saved = users.findById(user.getId()).orElseThrow();
        assertThat(saved.getNickname()).isEqualTo("초록");
        assertThat(saved.getName()).isEqualTo("OAuth이름");
        assertThat(saved.getProfileImageCode()).isEqualTo((short) 3);
        assertThat(owner(SECOND)).isNull();
        assertOwnershipIsConsistent();
    }

    @Test
    @DisplayName("현재 닉네임을 다시 요청하면 수정 시간과 후보 점유를 유지한다")
    void keepsCurrentNicknameWithoutWriting() {
        User user = saveUser(FIRST);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", user.getId());

        service.change(user.getId(), FIRST);

        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE id = ?", user.getId())).isEqualTo(before);
        assertThat(owner(FIRST)).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("새 후보 점유가 실패하면 회원 변경과 이전 후보 해제를 함께 롤백한다")
    void rollsBackNicknameAndOwnershipTogether() {
        User user = saveUser(FIRST);
        jdbc.execute("""
                ALTER TABLE nickname_candidates ADD CONSTRAINT test_nickname_change_failure
                CHECK (nickname <> '행복한 고양이' OR assigned_user_id IS NULL)
                """);
        try {
            assertThatThrownBy(() -> service.change(user.getId(), SECOND))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo(FIRST);
            assertThat(owner(FIRST)).isEqualTo(user.getId());
            assertThat(owner(SECOND)).isNull();
            assertOwnershipIsConsistent();
        } finally {
            jdbc.execute("ALTER TABLE nickname_candidates DROP CONSTRAINT test_nickname_change_failure");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SECOND, "같은별명"})
    @DisplayName("서로 다른 회원이 같은 닉네임을 요청하면 한 명만 성공하고 실패 회원의 점유는 유지한다")
    void rejectsConcurrentDuplicateNickname(String nickname) throws Exception {
        User first = saveUser(FIRST);
        User second = saveUser(THIRD);
        var ready = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondPid = new AtomicInteger();
        var transaction = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var winner = executor.submit(() -> transaction.executeWithoutResult(status -> {
                service.change(first.getId(), nickname);
                ready.countDown();
                await(commit);
            }));
            try {
                await(ready);
                var loser = executor.submit(() -> catchThrowableOfType(NicknameAlreadyInUseException.class,
                        () -> transaction.executeWithoutResult(status -> {
                            secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                            secondStarted.countDown();
                            service.change(second.getId(), nickname);
                        })));
                await(secondStarted);
                waitUntilBlocked(secondPid.get());
                commit.countDown();
                winner.get(10, TimeUnit.SECONDS);
                assertThat((Throwable) loser.get(10, TimeUnit.SECONDS)).isNotNull();
            } finally {
                commit.countDown();
            }
        }

        assertThat(users.findById(first.getId()).orElseThrow().getNickname()).isEqualTo(nickname);
        assertThat(users.findById(second.getId()).orElseThrow().getNickname()).isEqualTo(THIRD);
        assertThat(owner(THIRD)).isEqualTo(second.getId());
        assertThat(owner(FIRST)).isNull();
        assertOwnershipIsConsistent();
    }

    @Test
    @DisplayName("같은 회원의 동시 변경을 순서대로 처리하고 마지막 닉네임 하나만 점유한다")
    void serializesConcurrentChangesForSameUser() throws Exception {
        User user = saveUser(FIRST);
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                service.change(user.getId(), SECOND);
                return null;
            });
            var second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                service.change(user.getId(), THIRD);
                return null;
            });
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        String nickname = users.findById(user.getId()).orElseThrow().getNickname();
        assertThat(nickname).isIn(SECOND, THIRD);
        assertThat(owner(nickname)).isEqualTo(user.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nickname_candidates WHERE assigned_user_id = ?",
                Long.class, user.getId())).isEqualTo(1);
        assertOwnershipIsConsistent();
    }

    private User saveUser(String nickname) {
        User user = new User(null, "OAuth이름", NOW);
        user.assignInitialPublicProfile(nickname, 3, NOW);
        user = users.save(user);
        accounts.save(new OAuthAccount(user, OAuthProvider.KAKAO, user.getId().toString(), null, NOW));
        jdbc.update("UPDATE nickname_candidates SET assigned_user_id = ? WHERE nickname = ?", user.getId(), nickname);
        return user;
    }

    private UUID owner(String nickname) {
        return jdbc.queryForObject("SELECT assigned_user_id FROM nickname_candidates WHERE nickname = ?",
                UUID.class, nickname);
    }

    private void assertOwnershipIsConsistent() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM nickname_candidates c JOIN users u ON u.id = c.assigned_user_id
                WHERE u.nickname IS DISTINCT FROM c.nickname
                """, Long.class)).isZero();
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void waitUntilBlocked(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("동시 요청이 DB 잠금에서 대기하지 않았습니다.");
    }
}
