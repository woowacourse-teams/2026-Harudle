package com.harudle.auth.application;

import com.harudle.auth.infrastructure.RefreshTokenRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenCleanupService {

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenCleanupService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public int deleteExpiredTokens(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff는 필수입니다.");

        return refreshTokenRepository.deleteExpiredTokens(cutoff);
    }
}
