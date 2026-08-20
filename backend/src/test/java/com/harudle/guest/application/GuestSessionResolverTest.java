package com.harudle.guest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.guest.application.exception.GuestSessionExpiredException;
import com.harudle.guest.application.exception.GuestSessionRequiredException;
import com.harudle.guest.domain.GuestSession;
import com.harudle.guest.infrastructure.token.GuestSessionTokenHasher;
import com.harudle.guest.repository.GuestSessionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GuestSessionResolverTest {

    private static final UUID GUEST_USER_ID = UUID.fromString("a321bb09-816a-4941-906c-07b9c60db382");
    private static final String RAW_TOKEN = "guest-session-token";
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-08-19T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-19T00:00:00Z");

    @Mock
    private GuestSessionRepository guestSessionRepository;

    @Mock
    private GuestSessionTokenHasher tokenHasher;

    private GuestSessionResolver guestSessionResolver;

    @BeforeEach
    void setUp() {
        guestSessionResolver = new GuestSessionResolver(guestSessionRepository, tokenHasher);
        when(tokenHasher.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
    }

    @Test
    @DisplayName("원본 토큰을 해시해 유효한 게스트 세션을 찾는다")
    void resolveGuestSession() {
        GuestSession session = createSession();
        when(guestSessionRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(session));

        GuestSession resolved = guestSessionResolver.resolve(RAW_TOKEN, CREATED_AT.plusSeconds(1));

        assertThat(resolved).isSameAs(session);
        verify(guestSessionRepository).findByTokenHash(TOKEN_HASH);
    }

    @Test
    @DisplayName("생성 트랜잭션에서는 게스트 세션을 쓰기 잠금으로 찾는다")
    void resolveGuestSessionForUpdate() {
        GuestSession session = createSession();
        when(guestSessionRepository.findByTokenHashForUpdate(TOKEN_HASH))
                .thenReturn(Optional.of(session));

        GuestSession resolved = guestSessionResolver.resolveForUpdate(
                RAW_TOKEN,
                CREATED_AT.plusSeconds(1)
        );

        assertThat(resolved).isSameAs(session);
        verify(guestSessionRepository).findByTokenHashForUpdate(TOKEN_HASH);
    }

    @Test
    @DisplayName("토큰에 해당하는 게스트 세션이 없으면 인증 오류를 반환한다")
    void rejectUnknownGuestSession() {
        when(guestSessionRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guestSessionResolver.resolve(
                RAW_TOKEN,
                CREATED_AT.plusSeconds(1)
        )).isInstanceOf(GuestSessionRequiredException.class);
    }

    @Test
    @DisplayName("만료된 게스트 세션은 사용할 수 없다")
    void rejectExpiredGuestSession() {
        when(guestSessionRepository.findByTokenHash(TOKEN_HASH))
                .thenReturn(Optional.of(createSession()));

        assertThatThrownBy(() -> guestSessionResolver.resolve(RAW_TOKEN, EXPIRES_AT))
                .isInstanceOf(GuestSessionExpiredException.class);
    }

    private GuestSession createSession() {
        return GuestSession.create(
                GUEST_USER_ID,
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );
    }
}
