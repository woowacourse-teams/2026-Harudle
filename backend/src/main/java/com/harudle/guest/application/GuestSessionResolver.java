package com.harudle.guest.application;

import com.harudle.guest.application.exception.GuestSessionExpiredException;
import com.harudle.guest.application.exception.GuestSessionRequiredException;
import com.harudle.guest.domain.GuestSession;
import com.harudle.guest.infrastructure.token.GuestSessionTokenHasher;
import com.harudle.guest.repository.GuestSessionRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestSessionResolver {

    private final GuestSessionRepository guestSessionRepository;
    private final GuestSessionTokenHasher tokenHasher;

    public GuestSessionResolver(
            GuestSessionRepository guestSessionRepository,
            GuestSessionTokenHasher tokenHasher
    ) {
        this.guestSessionRepository = guestSessionRepository;
        this.tokenHasher = tokenHasher;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public GuestSession resolve(String rawToken, Instant now) {
        validateRawToken(rawToken);
        String tokenHash = tokenHasher.hash(rawToken);
        GuestSession session = guestSessionRepository.findByTokenHash(tokenHash)
                .orElseThrow(GuestSessionRequiredException::new);
        validateExpiration(session, now);
        return session;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public GuestSession resolveForUpdate(String rawToken, Instant now) {
        validateRawToken(rawToken);
        String tokenHash = tokenHasher.hash(rawToken);
        GuestSession session = guestSessionRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(GuestSessionRequiredException::new);
        validateExpiration(session, now);
        return session;
    }

    private static void validateExpiration(GuestSession session, Instant now) {
        Objects.requireNonNull(now, "현재 시각은 필수입니다.");
        if (session.isExpiredAt(now)) {
            throw new GuestSessionExpiredException();
        }
    }

    private static void validateRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new GuestSessionRequiredException();
        }
    }
}
