package com.harudle.auth.application;

import com.harudle.auth.domain.RefreshToken;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.RefreshTokenRepository;
import com.harudle.auth.infrastructure.token.RefreshTokenGenerator;
import com.harudle.auth.infrastructure.token.RefreshTokenHasher;
import com.harudle.common.security.AuthProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final RefreshTokenHasher refreshTokenHasher;
    private final Duration refreshTokenTtl;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenGenerator refreshTokenGenerator,
            RefreshTokenHasher refreshTokenHasher,
            AuthProperties authProperties
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.refreshTokenHasher = refreshTokenHasher;
        this.refreshTokenTtl = extractRefreshTokenTtl(authProperties);
    }

    @Transactional
    public IssuedRefreshToken issue(User user, Instant now) {
        Objects.requireNonNull(user, "user는 필수입니다.");
        Objects.requireNonNull(now, "now는 필수입니다.");
        validateActiveUser(user);

        return issueNewToken(user, now);
    }

    @Transactional
    public IssuedRefreshToken rotate(String rawToken, Instant now) {
        Objects.requireNonNull(now, "now는 필수입니다.");

        String tokenHash = hashRawToken(rawToken);
        RefreshToken refreshToken = findRefreshToken(tokenHash);
        validateUsableToken(refreshToken, now);
        validateActiveUser(refreshToken.getUser());

        refreshToken.revoke(now);

        return issueNewToken(refreshToken.getUser(), now);
    }

    @Transactional
    public void revoke(String rawToken, Instant now) {
        Objects.requireNonNull(now, "now는 필수입니다.");

        if (rawToken == null || rawToken.isBlank()) {
            return;
        }

        String tokenHash = refreshTokenHasher.hash(rawToken);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .ifPresent(refreshToken -> refreshToken.revoke(now));
    }

    private IssuedRefreshToken issueNewToken(User user, Instant now) {
        String rawToken = refreshTokenGenerator.generate();
        String tokenHash = refreshTokenHasher.hash(rawToken);
        Instant expiresAt = now.plus(refreshTokenTtl);

        RefreshToken refreshToken = new RefreshToken(
                user,
                tokenHash,
                expiresAt,
                now
        );
        refreshTokenRepository.save(refreshToken);

        return new IssuedRefreshToken(rawToken, expiresAt);
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
