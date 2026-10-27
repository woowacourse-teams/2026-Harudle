package com.harudle.auth.application;

import com.harudle.auth.domain.RefreshToken;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.RefreshTokenRepository;
import com.harudle.auth.infrastructure.token.RefreshTokenGenerator;
import com.harudle.auth.infrastructure.token.RefreshTokenHasher;
import com.harudle.common.security.AuthProperties;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private static final String LOCK_ACTIVE_SESSION = """
            SELECT s.id FROM auth_sessions s JOIN refresh_tokens rt ON rt.session_id = s.id
            WHERE rt.token_hash = ? AND s.revoked_at IS NULL FOR UPDATE OF s
            """;
    private static final String CANCEL_SESSION_JOBS = """
            UPDATE notification_outbox o
            SET status = 'CANCELLED', lock_token = NULL, locked_until = NULL,
                sent_at = NULL, updated_at = ?
            FROM push_registrations r
            WHERE o.push_registration_id = r.id AND r.session_id = ?
              AND o.status IN ('PENDING', 'PROCESSING')
            """;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final RefreshTokenHasher refreshTokenHasher;
    private final Duration refreshTokenTtl;
    private final AccountAccessPolicy accountAccess;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager,
            RefreshTokenGenerator refreshTokenGenerator,
            RefreshTokenHasher refreshTokenHasher,
            AuthProperties authProperties,
            AccountAccessPolicy accountAccess
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.refreshTokenHasher = refreshTokenHasher;
        this.refreshTokenTtl = extractRefreshTokenTtl(authProperties);
        this.accountAccess = accountAccess;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public IssuedRefreshToken issue(User user, Instant now) {
        Objects.requireNonNull(user, "user는 필수입니다.");
        Objects.requireNonNull(now, "now는 필수입니다.");
        validateActiveUser(user);
        accountAccess.requireAllowed(user.getId());

        return issueNewToken(user, createSession(user, now), now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RotatedRefreshToken rotate(String rawToken, Instant now) {
        Objects.requireNonNull(now, "now는 필수입니다.");

        String tokenHash = hashRawToken(rawToken);
        UUID candidateUserId = refreshTokenRepository.findUserIdByTokenHash(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::new);
        // 제재와 같은 회원 잠금을 세션/토큰보다 먼저 얻어 발급과 제재의 잠금 순서를 고정한다.
        accountAccess.requireAllowed(candidateUserId);
        UUID activeSessionId = lockActiveSession(tokenHash);
        RefreshToken refreshToken = findRefreshToken(tokenHash);
        if (!candidateUserId.equals(refreshToken.getUser().getId())) {
            throw new InvalidRefreshTokenException();
        }
        validateUsableToken(refreshToken, now);
        validateActiveUser(refreshToken.getUser());
        if (refreshToken.getSessionId() != null && activeSessionId == null) {
            throw new InvalidRefreshTokenException();
        }

        UUID userId = refreshToken.getUser().getId();
        refreshToken.revoke(now);
        UUID sessionId = refreshToken.getSessionId();
        if (sessionId == null) {
            sessionId = createSession(refreshToken.getUser(), now);
        }
        IssuedRefreshToken issuedRefreshToken = issueNewToken(refreshToken.getUser(), sessionId, now);

        return new RotatedRefreshToken(userId, sessionId, issuedRefreshToken);
    }

    @Transactional
    public void revoke(String rawToken, Instant now) {
        Objects.requireNonNull(now, "now는 필수입니다.");

        if (rawToken == null || rawToken.isBlank()) {
            return;
        }

        String tokenHash = refreshTokenHasher.hash(rawToken);
        UUID activeSessionId = lockActiveSession(tokenHash);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .ifPresent(refreshToken -> revokeTokenOrSession(refreshToken, activeSessionId, now));
    }

    private UUID lockActiveSession(String tokenHash) {
        entityManager.flush();
        List<UUID> sessions = jdbcTemplate.queryForList(LOCK_ACTIVE_SESSION, UUID.class, tokenHash);
        if (sessions.isEmpty()) {
            return null;
        }
        return sessions.getFirst();
    }

    private void revokeTokenOrSession(RefreshToken token, UUID activeSessionId, Instant now) {
        if (token.getSessionId() == null) {
            token.revoke(now);
            return;
        }
        if (activeSessionId != null) {
            revokeSession(activeSessionId, now);
        }
    }

    private void revokeSession(UUID sessionId, Instant now) {
        Timestamp revokedAt = Timestamp.from(now);
        jdbcTemplate.update("UPDATE auth_sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                revokedAt, sessionId);
        jdbcTemplate.update("UPDATE refresh_tokens SET revoked_at = ? WHERE session_id = ? AND revoked_at IS NULL",
                revokedAt, sessionId);
        jdbcTemplate.update("UPDATE push_registrations SET revoked_at = ?, updated_at = ? "
                + "WHERE session_id = ? AND revoked_at IS NULL", revokedAt, revokedAt, sessionId);
        jdbcTemplate.update(CANCEL_SESSION_JOBS, revokedAt, sessionId);
    }

    private IssuedRefreshToken issueNewToken(User user, UUID sessionId, Instant now) {
        String rawToken = refreshTokenGenerator.generate();
        String tokenHash = refreshTokenHasher.hash(rawToken);
        Instant expiresAt = now.plus(refreshTokenTtl);

        RefreshToken refreshToken = new RefreshToken(
                user,
                sessionId,
                tokenHash,
                expiresAt,
                now
        );
        refreshTokenRepository.save(refreshToken);

        return new IssuedRefreshToken(rawToken, expiresAt);
    }

    private UUID createSession(User user, Instant now) {
        UUID sessionId = UUID.randomUUID();
        entityManager.flush();
        jdbcTemplate.update("INSERT INTO auth_sessions (id, user_id, created_at) VALUES (?, ?, ?)",
                sessionId, user.getId(), Timestamp.from(now));
        return sessionId;
    }

    private String hashRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }

        return refreshTokenHasher.hash(rawToken);
    }

    private RefreshToken findRefreshToken(String tokenHash) {
        return refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::new);
    }

    private void validateUsableToken(RefreshToken refreshToken, Instant now) {
        if (refreshToken.canUseAt(now)) {
            return;
        }

        throw new InvalidRefreshTokenException();
    }

    private void validateActiveUser(User user) {
        if (!user.isDeleted()) {
            return;
        }

        throw new InactiveUserException();
    }

    private Duration extractRefreshTokenTtl(AuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties는 필수입니다.");

        Duration ttl = Objects.requireNonNull(
                authProperties.refreshToken(),
                "refreshToken 설정은 필수입니다."
        ).ttl();
        Objects.requireNonNull(ttl, "refreshToken ttl은 필수입니다.");

        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("refreshToken ttl은 양수여야 합니다.");
        }

        return ttl;
    }

}
