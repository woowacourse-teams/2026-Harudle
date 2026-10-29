package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.domain.RefreshToken;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.RefreshTokenRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.support.ServiceIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Transactional
class RefreshTokenCleanupPersistenceTest extends ServiceIntegrationTestSupport {

    private static final Instant CUTOFF = Instant.parse("2099-08-26T10:00:00Z");
    private static final Instant CREATED_AT = CUTOFF.minusSeconds(3_600);

    @Autowired
    private RefreshTokenCleanupService cleanupService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(new User(null, "토큰 정리 테스트", CREATED_AT));
    }

    @Test
    @DisplayName("만료 시각이 기준 시각 이하인 토큰만 삭제한다")
    void deletesTokensAtOrBeforeCutoff() {
        Long expiredId = saveToken(CUTOFF.minusSeconds(1), false);
        Long boundaryId = saveToken(CUTOFF, false);
        Long validId = saveToken(CUTOFF.plusSeconds(1), false);
        flushAndClear();

        int deletedCount = cleanupService.deleteExpiredTokens(CUTOFF);
        flushAndClear();

        assertThat(deletedCount).isGreaterThanOrEqualTo(2);
        assertThat(refreshTokenRepository.findById(expiredId)).isEmpty();
        assertThat(refreshTokenRepository.findById(boundaryId)).isEmpty();
        assertThat(refreshTokenRepository.findById(validId)).isPresent();
        assertThat(userRepository.findById(user.getId())).isPresent();
    }

    @Test
    @DisplayName("폐기 여부와 관계없이 만료 시각으로 삭제 대상을 판단한다")
    void keepsRevokedTokenUntilExpiration() {
        Long expiredRevokedId = saveToken(CUTOFF.minusSeconds(1), true);
        Long validRevokedId = saveToken(CUTOFF.plusSeconds(1), true);
        flushAndClear();

        cleanupService.deleteExpiredTokens(CUTOFF);
        flushAndClear();

        assertThat(refreshTokenRepository.findById(expiredRevokedId)).isEmpty();
        assertThat(refreshTokenRepository.findById(validRevokedId))
                .hasValueSatisfying(token -> assertThat(token.isRevoked()).isTrue());
    }

    @Test
    @DisplayName("삭제 대상이 없으면 0건을 반환하고 유효한 토큰을 유지한다")
    void returnsZeroWhenNoTokensAreExpired() {
        Long validId = saveToken(CUTOFF.plusSeconds(1), false);
        flushAndClear();
        // 공유 DB의 기존 만료 토큰도 이 테스트의 트랜잭션 안에서만 정리한다.
        cleanupService.deleteExpiredTokens(CUTOFF);
        flushAndClear();

        int deletedCount = cleanupService.deleteExpiredTokens(CUTOFF);
        flushAndClear();

        assertThat(deletedCount).isZero();
        assertThat(refreshTokenRepository.findById(validId)).isPresent();
    }

    @Test
    @DisplayName("같은 기준 시각으로 다시 실행해도 이미 삭제한 토큰을 다시 처리하지 않는다")
    void repeatedCleanupReturnsZero() {
        Long expiredId = saveToken(CUTOFF.minusSeconds(1), false);
        flushAndClear();

        int firstDeletedCount = cleanupService.deleteExpiredTokens(CUTOFF);
        flushAndClear();
        int secondDeletedCount = cleanupService.deleteExpiredTokens(CUTOFF);
        flushAndClear();

        assertThat(firstDeletedCount).isGreaterThanOrEqualTo(1);
        assertThat(secondDeletedCount).isZero();
        assertThat(refreshTokenRepository.findById(expiredId)).isEmpty();
    }

    private Long saveToken(Instant expiresAt, boolean revoked) {
        String tokenHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        RefreshToken token = new RefreshToken(user, tokenHash, expiresAt, CREATED_AT);
        if (revoked) {
            token.revoke(CREATED_AT.plusSeconds(1));
        }
        return refreshTokenRepository.save(token).getId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
