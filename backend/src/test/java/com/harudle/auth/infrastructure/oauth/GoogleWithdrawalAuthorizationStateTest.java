package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class GoogleWithdrawalAuthorizationStateTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);
    private final GoogleWithdrawalAuthorizationState state = new GoogleWithdrawalAuthorizationState(clock);
    private final MockHttpSession session = new MockHttpSession();

    @Test
    void bindsOnlyTheMatchingRequestAndConsumesItOnce() {
        UUID userId = UUID.randomUUID();
        String requestId = state.begin(session, userId);

        assertThat(state.bind(session, "different", "oauth-state")).isFalse();
        assertThat(state.bind(session, requestId, "oauth-state")).isTrue();
        assertThat(state.bind(session, requestId, "other-state")).isFalse();
        assertThat(state.consume(session, "other-state")).isEmpty();
        assertThat(state.consume(session, "oauth-state"))
                .contains(new GoogleWithdrawalAuthorizationState.Result(userId, false));
        assertThat(state.consume(session, "oauth-state")).isEmpty();
    }

    @Test
    void identifiesExpiredWithdrawalWithoutTreatingItAsRegularLogin() {
        UUID userId = UUID.randomUUID();
        String requestId = state.begin(session, userId);
        assertThat(state.bind(session, requestId, "oauth-state")).isTrue();
        var later = new GoogleWithdrawalAuthorizationState(Clock.offset(clock, Duration.ofMinutes(11)));

        assertThat(later.consume(session, "oauth-state"))
                .contains(new GoogleWithdrawalAuthorizationState.Result(userId, true));
        assertThat(later.consume(session, "oauth-state")).isEmpty();
    }
}
