package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.port.AccountRestrictionReader;
import com.harudle.auth.domain.RefreshToken;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.RefreshTokenRepository;
import com.harudle.auth.infrastructure.token.RefreshTokenGenerator;
import com.harudle.auth.infrastructure.token.RefreshTokenHasher;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.RefreshTokenProperties;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class RefreshTokenRestrictionTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final RefreshTokenGenerator generator = mock(RefreshTokenGenerator.class);
    private final RefreshTokenHasher hasher = mock(RefreshTokenHasher.class);
    private final AccountRestrictionReader restrictions = mock(AccountRestrictionReader.class);
    private final User user = new User("test@example.invalid", "회원", NOW.minusSeconds(60));
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        AuthProperties properties = mock(AuthProperties.class);
        when(properties.refreshToken()).thenReturn(new RefreshTokenProperties("refresh_token", "/api/v1/auth",
                false, "Lax", Duration.ofDays(14)));
        service = new RefreshTokenService(tokens, jdbc, entities, generator, hasher, properties,
                new AccountAccessPolicy(restrictions));
    }

    @Test
    @DisplayName("정지 회원에게 새 로그인 세션이나 refresh token을 발급하지 않는다")
    void rejectsSessionAndTokenIssue() {
        when(restrictions.isSuspended(user.getId())).thenReturn(true);
        assertThatThrownBy(() -> service.issue(user, NOW)).isInstanceOf(AccountSuspendedException.class);
        verifyNoInteractions(tokens, jdbc, entities, generator, hasher);
    }

    @Test
    @DisplayName("정지 중 refresh 회전은 기존 토큰 상태를 바꾸거나 새 토큰을 만들지 않는다")
    void rejectsRotationBeforeRevokingToken() {
        RefreshToken token = existingToken();
        when(restrictions.isSuspended(user.getId())).thenReturn(true);
        assertThatThrownBy(() -> service.rotate("raw-token", NOW)).isInstanceOf(AccountSuspendedException.class);
        assertThat(token.isRevoked()).isFalse();
        verify(tokens, never()).findByTokenHash(anyString());
        verify(tokens, never()).save(any());
        verifyNoInteractions(jdbc, entities, generator);
    }

    @Test
    @DisplayName("refresh의 제재 조회 장애는 정지 응답으로 바꾸지 않고 새 토큰 없이 실패한다")
    void rejectsUnavailableRestrictionRead() {
        RefreshToken token = existingToken();
        when(restrictions.isSuspended(user.getId())).thenThrow(new DataAccessResourceFailureException("조회 실패"));
        assertThatThrownBy(() -> service.rotate("raw-token", NOW))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(token.isRevoked()).isFalse();
        verify(tokens, never()).findByTokenHash(anyString());
        verify(tokens, never()).save(any());
        verifyNoInteractions(jdbc, entities, generator);
    }

    @Test
    @DisplayName("refresh 회전은 회원 제재 잠금을 세션과 토큰 잠금보다 먼저 얻는다")
    void acquireAccountGateBeforeSessionAndTokenLocks() {
        RefreshToken token = existingToken();
        when(generator.generate()).thenReturn("new-token");
        when(hasher.hash("new-token")).thenReturn("b".repeat(64));

        service.rotate("raw-token", NOW);

        var order = inOrder(tokens, restrictions, entities, jdbc);
        order.verify(tokens).findUserIdByTokenHash("a".repeat(64));
        order.verify(restrictions).isSuspended(user.getId());
        order.verify(entities).flush();
        order.verify(jdbc).queryForList(anyString(), eq(UUID.class), eq("a".repeat(64)));
        order.verify(tokens).findByTokenHash("a".repeat(64));
        order.verify(tokens).save(any());
        assertThat(token.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("비잠금 회원 projection과 실제 잠근 토큰의 회원이 다르면 회전을 거절한다")
    void recheckTheLockedTokenOwnerBeforeRotation() {
        RefreshToken token = existingToken();
        UUID projectedUser = UUID.randomUUID();
        when(tokens.findUserIdByTokenHash("a".repeat(64))).thenReturn(Optional.of(projectedUser));

        assertThatThrownBy(() -> service.rotate("raw-token", NOW)).isInstanceOf(InvalidRefreshTokenException.class);

        verify(restrictions).isSuspended(projectedUser);
        verify(tokens, never()).save(any());
        verifyNoInteractions(generator);
        assertThat(token.isRevoked()).isFalse();
    }

    private RefreshToken existingToken() {
        UUID sessionId = UUID.randomUUID();
        RefreshToken token = new RefreshToken(
                user, sessionId, "a".repeat(64), NOW.plusSeconds(60), NOW.minusSeconds(60));
        when(hasher.hash("raw-token")).thenReturn("a".repeat(64));
        when(tokens.findUserIdByTokenHash("a".repeat(64))).thenReturn(Optional.of(user.getId()));
        when(jdbc.queryForList(anyString(), eq(UUID.class), any())).thenReturn(List.of(sessionId));
        when(tokens.findByTokenHash("a".repeat(64))).thenReturn(Optional.of(token));
        return token;
    }
}
