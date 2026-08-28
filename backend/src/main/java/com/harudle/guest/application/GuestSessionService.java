package com.harudle.guest.application;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.guest.configuration.GuestSessionProperties;
import com.harudle.guest.domain.GuestSession;
import com.harudle.guest.infrastructure.token.GuestSessionTokenGenerator;
import com.harudle.guest.infrastructure.token.GuestSessionTokenHasher;
import com.harudle.guest.repository.GuestSessionRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestSessionService {

    private static final String GUEST_USER_NAME = "게스트";

    private final UserRepository userRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final GuestSessionTokenGenerator tokenGenerator;
    private final GuestSessionTokenHasher tokenHasher;
    private final GuestSessionProperties properties;

    public GuestSessionService(
            UserRepository userRepository,
            GuestSessionRepository guestSessionRepository,
            GuestSessionTokenGenerator tokenGenerator,
            GuestSessionTokenHasher tokenHasher,
            GuestSessionProperties properties
    ) {
        this.userRepository = userRepository;
        this.guestSessionRepository = guestSessionRepository;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.properties = properties;
    }

    @Transactional
    public IssuedGuestSession issueOrReuse(String currentRawToken, Instant now) {
        Objects.requireNonNull(now, "now는 필수입니다.");

        return findReusableSession(currentRawToken, now)
                .orElseGet(() -> issueNewSession(now));
    }

    private Optional<IssuedGuestSession> findReusableSession(String currentRawToken, Instant now) {
        if (currentRawToken == null || currentRawToken.isBlank()) {
            return Optional.empty();
        }

        String tokenHash = tokenHasher.hash(currentRawToken);

        return guestSessionRepository.findByTokenHash(tokenHash)
                .filter(session -> !session.isExpiredAt(now))
                .map(session -> new IssuedGuestSession(
                        currentRawToken,
                        session.getExpiresAt()
                ));
    }

    private IssuedGuestSession issueNewSession(Instant now) {
        User guestUser = createGuestUser(now);
        String rawToken = tokenGenerator.generate();
        String tokenHash = tokenHasher.hash(rawToken);
        Instant expiresAt = now.plus(properties.ttl());

        GuestSession guestSession = GuestSession.create(
                guestUser.getId(),
                tokenHash,
                expiresAt,
                now
        );

        guestSessionRepository.save(guestSession);

        return new IssuedGuestSession(rawToken, expiresAt);
    }

    private User createGuestUser(Instant now) {
        User guestUser = new User(
                null,
                GUEST_USER_NAME,
                now
        );

        return userRepository.save(guestUser);
    }
}
