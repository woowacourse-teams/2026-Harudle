package com.harudle.auth.infrastructure.oauth;

import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class GoogleWithdrawalAuthorizationState {

    private static final String ATTRIBUTE = GoogleWithdrawalAuthorizationState.class.getName();
    private static final Duration VALIDITY = Duration.ofMinutes(10);
    private final Clock clock;

    public GoogleWithdrawalAuthorizationState(@Qualifier("authClock") Clock clock) {
        this.clock = clock;
    }

    public String begin(HttpSession session, UUID userId) {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        String requestId = UUID.randomUUID().toString();
        session.setAttribute(ATTRIBUTE, new Pending(userId, requestId, null, clock.instant().plus(VALIDITY)));
        return requestId;
    }

    public boolean bind(HttpSession session, String requestId, String oauthState) {
        if (requestId == null || requestId.isBlank() || oauthState == null || oauthState.isBlank()) {
            return false;
        }
        Object value = session.getAttribute(ATTRIBUTE);
        if (!(value instanceof Pending pending) || pending.oauthState() != null
                || !pending.requestId().equals(requestId) || !pending.expiresAt().isAfter(clock.instant())) {
            return false;
        }
        session.setAttribute(ATTRIBUTE, new Pending(pending.userId(), pending.requestId(), oauthState, pending.expiresAt()));
        return true;
    }

    public Optional<Result> consume(HttpSession session, String oauthState) {
        Object value = session.getAttribute(ATTRIBUTE);
        if (!(value instanceof Pending pending) || pending.oauthState() == null
                || !pending.oauthState().equals(oauthState)) {
            return Optional.empty();
        }
        session.removeAttribute(ATTRIBUTE);
        return Optional.of(new Result(pending.userId(), !pending.expiresAt().isAfter(clock.instant())));
    }

    public record Result(UUID userId, boolean expired) {}

    private record Pending(UUID userId, String requestId, String oauthState, Instant expiresAt) {}
}
