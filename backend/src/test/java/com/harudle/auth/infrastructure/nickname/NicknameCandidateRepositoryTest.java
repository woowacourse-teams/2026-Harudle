package com.harudle.auth.infrastructure.nickname;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.domain.User;
import com.harudle.auth.domain.exception.NicknamePoolBusyException;
import com.harudle.auth.domain.exception.NicknamePoolExhaustedException;
import com.harudle.auth.infrastructure.UserRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(NicknameCandidateRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NicknameCandidateRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    private static final String FIRST = "신나는 수달";
    private static final String SECOND = "행복한 고양이";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired
    private NicknameCandidateRepository repository;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM nickname_candidates");
        // 이 테스트에서 만든 회원만 정리한다.
        jdbc.update("DELETE FROM users WHERE name = '후보테스트'");
        jdbc.update("""
                INSERT INTO nickname_candidates (nickname, allocation_order)
                VALUES (?, 1), (?, 2)
                """, FIRST, SECOND);
    }

    @Test
    @DisplayName("호출자의 트랜잭션이 없으면 후보를 확보할 수 없다")
    void requiresCallerTransaction() {
        assertThatThrownBy(repository::lockNextAvailable).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("후보 확보 후 회원 저장과 점유를 함께 커밋하고 다음 요청은 다른 후보를 받는다")
    void assignsCandidateAndSkipsOwnedCandidate() {
        UUID userId = transaction.execute(status -> createAndAssign(repository.lockNextAvailable()).getId());

        assertThat(owner(FIRST)).isEqualTo(userId);
        String next = transaction.execute(status -> repository.lockNextAvailable());
        assertThat(next).isEqualTo(SECOND);
    }

    @Test
    @DisplayName("점유 기록을 포함한 트랜잭션 실패는 회원 저장과 후보 점유를 함께 롤백한다")
    void rollsBackUserAndOwnershipTogether() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            createAndAssign(repository.lockNextAvailable());
            throw new IllegalStateException("후속 저장 실패");
        })).isInstanceOf(IllegalStateException.class).hasMessage("후속 저장 실패");

        assertThat(owner(FIRST)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE name = '후보테스트'", Long.class)).isZero();
    }

    @Test
    @DisplayName("후보 소유자가 비어 있어도 활성 회원이 쓰는 이름은 확보하지 않는다")
    void skipsActiveNicknameEvenIfOwnershipIsMissing() {
        transaction.executeWithoutResult(status -> saveUser(FIRST));

        String next = transaction.execute(status -> repository.lockNextAvailable());
        assertThat(next).isEqualTo(SECOND);
    }

    @Test
    @DisplayName("회원의 현재 닉네임과 다른 후보를 점유하거나 타인의 점유를 덮어쓸 수 없다")
    void rejectsMismatchedAndForeignOwnership() {
        UUID userId = transaction.execute(status -> createAndAssign(FIRST).getId());
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> repository.assignToUser(SECOND, userId)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> repository.assignToUser(FIRST, UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(owner(FIRST)).isEqualTo(userId);
    }

    @Test
    @DisplayName("같은 회원의 동일 점유 기록은 반복해도 안전하고 사용 중인 이름은 해제하지 않는다")
    void repeatsAssignmentButDoesNotReleaseUsedNickname() {
        UUID userId = transaction.execute(status -> createAndAssign(FIRST).getId());
        transaction.executeWithoutResult(status -> repository.assignToUser(FIRST, userId));
        Boolean released = transaction.execute(status -> repository.releaseFromUser(FIRST, userId));

        assertThat(released).isFalse();
        assertThat(owner(FIRST)).isEqualTo(userId);
    }

    @Test
    @DisplayName("JPA 닉네임 변경을 flush한 뒤에만 본인의 이전 점유를 해제한다")
    void releasesAfterNicknameChangeWithoutExplicitFlush() {
        UUID userId = transaction.execute(status -> createAndAssign(FIRST).getId());
        transaction.executeWithoutResult(status -> {
            users.findByIdForUpdate(userId).orElseThrow().changeNickname("초록", NOW.plusSeconds(1));
            assertThat(repository.releaseFromUser(FIRST, UUID.randomUUID())).isFalse();
            assertThat(repository.releaseFromUser(FIRST, userId)).isTrue();
            assertThat(repository.releaseFromUser(FIRST, userId)).isFalse();
        });

        assertThat(owner(FIRST)).isNull();
        String next = transaction.execute(status -> repository.lockNextAvailable());
        assertThat(next).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("후보 간 이름 변경은 이전 점유 해제 후 새 점유를 기록한다")
    void movesOwnershipAfterNicknameChange() {
        UUID userId = transaction.execute(status -> createAndAssign(FIRST).getId());
        transaction.executeWithoutResult(status -> {
            String nickname = repository.lockNextAvailable();
            users.findByIdForUpdate(userId).orElseThrow().changeNickname(nickname, NOW.plusSeconds(1));
            assertThat(repository.releaseFromUser(FIRST, userId)).isTrue();
            repository.assignToUser(nickname, userId);
        });
        assertThat(owner(FIRST)).isNull();
        assertThat(owner(SECOND)).isEqualTo(userId);
    }

    @Test
    @DisplayName("탈퇴 저장 후 점유를 해제하고 후보를 재사용할 수 있다")
    void releasesAfterSoftDeletion() {
        UUID userId = transaction.execute(status -> createAndAssign(FIRST).getId());
        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", userId);
            assertThat(repository.releaseFromUser(FIRST, userId)).isTrue();
        });
        String next = transaction.execute(status -> repository.lockNextAvailable());
        assertThat(next).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("확보 가능한 후보가 실제로 없으면 소진 예외를 발생시킨다")
    void reportsExhaustion() {
        transaction.executeWithoutResult(status -> {
            createAndAssign(FIRST);
            createAndAssign(SECOND);
        });
        assertThatThrownBy(() -> transaction.execute(status -> repository.lockNextAvailable()))
                .isInstanceOf(NicknamePoolExhaustedException.class);
    }

    @Test
    @DisplayName("회원당 후보 점유는 하나이고 다른 후보를 직접 점유하는 SQL도 실패한다")
    void enforcesOneCandidatePerUser() {
        UUID userId = transaction.execute(status -> createAndAssign(FIRST).getId());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE nickname_candidates SET assigned_user_id = ? WHERE nickname = ?
                """, userId, SECOND)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("첫 요청이 후보를 확보 중이면 두 번째 요청은 기다리지 않고 다음 후보를 배정한다")
    void skipsCandidateLockedByConcurrentSignup() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> transaction.execute(status -> {
                String nickname = repository.lockNextAvailable();
                locked.countDown();
                await(release);
                return createAndAssign(nickname).getId();
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                UUID secondId = transaction.execute(status -> createAndAssign(repository.lockNextAvailable()).getId());
                assertThat(owner(SECOND)).isEqualTo(secondId);
                release.countDown();
                assertThat(ownerAfter(first.get(10, TimeUnit.SECONDS))).isEqualTo(FIRST);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @DisplayName("마지막 빈 후보가 잠겨 있으면 일시 경합으로 구분하고 잠금 종료 후 다시 확보한다")
    void reportsBusyInsteadOfExhaustionWhenLastCandidateIsLocked() throws Exception {
        jdbc.update("DELETE FROM nickname_candidates WHERE nickname = ?", SECOND);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
                repository.lockNextAvailable();
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> transaction.execute(status -> repository.lockNextAvailable()))
                        .isInstanceOf(NicknamePoolBusyException.class);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
        String next = transaction.execute(status -> repository.lockNextAvailable());
        assertThat(next).isEqualTo(FIRST);
    }

    private User saveUser(String nickname) {
        User user = new User(null, "후보테스트", NOW);
        user.assignInitialPublicProfile(nickname, 1, NOW);
        return users.save(user);
    }

    private User createAndAssign(String nickname) {
        User user = saveUser(nickname);
        repository.assignToUser(nickname, user.getId());
        return user;
    }

    private UUID owner(String nickname) {
        return jdbc.queryForObject("SELECT assigned_user_id FROM nickname_candidates WHERE nickname = ?", UUID.class, nickname);
    }

    private String ownerAfter(UUID userId) {
        return jdbc.queryForObject("SELECT nickname FROM nickname_candidates WHERE assigned_user_id = ?", String.class, userId);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 중단", exception);
        }
    }
}
