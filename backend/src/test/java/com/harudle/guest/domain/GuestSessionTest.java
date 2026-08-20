package com.harudle.guest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GuestSessionTest {

    private static final UUID GUEST_USER_ID = UUID.fromString("a321bb09-816a-4941-906c-07b9c60db382");
    private static final UUID DIARY_ID = UUID.fromString("593363cb-1dc3-46bc-a858-5926f7601ca9");
    private static final UUID ANOTHER_DIARY_ID = UUID.fromString("5b751eed-6b64-4e86-b9a6-af9e90b9a03a");
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-08-19T00:00:00Z");
    private static final Instant USED_AT = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-19T00:00:00Z");

    @Test
    @DisplayName("게스트 세션을 생성한다")
    void createsGuestSession() {
        GuestSession session = GuestSession.create(
                GUEST_USER_ID,
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );

        assertThat(session.getId()).isNotNull();
        assertThat(session.getGuestUserId()).isEqualTo(GUEST_USER_ID);
        assertThat(session.getTokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(session.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(session.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(session.getUpdatedAt()).isEqualTo(CREATED_AT);

        assertThat(session.getDiaryId()).isNull();
        assertThat(session.getUsedAt()).isNull();
        assertThat(session.isUsed()).isFalse();
    }

    @Test
    @DisplayName("만료 시각 전에는 게스트 세션을 사용할 수 있다")
    void isNotExpiredBeforeExpiration() {
        GuestSession session = createSession();

        Instant beforeExpiration = Instant.parse("2026-09-18T23:59:59Z");

        assertThat(session.isExpiredAt(beforeExpiration)).isFalse();
    }

    @Test
    @DisplayName("만료 시각부터 게스트 세션이 만료된다")
    void isExpiredAtExpiration() {
        GuestSession session = createSession();

        assertThat(session.isExpiredAt(EXPIRES_AT)).isTrue();
    }

    @Test
    @DisplayName("만료 시각 이후에는 게스트 세션이 만료된다")
    void isExpiredAfterExpiration() {
        GuestSession session = createSession();

        Instant afterExpiration = Instant.parse("2026-09-19T00:00:01Z");

        assertThat(session.isExpiredAt(afterExpiration)).isTrue();
    }

    @Test
    @DisplayName("게스트 세션을 일기에 사용한다")
    void usesGuestSessionForDiary() {
        GuestSession session = createSession();

        session.useForDiary(DIARY_ID, USED_AT);

        assertThat(session.getDiaryId()).isEqualTo(DIARY_ID);
        assertThat(session.getUsedAt()).isEqualTo(USED_AT);
        assertThat(session.getUpdatedAt()).isEqualTo(USED_AT);
        assertThat(session.isUsed()).isTrue();
        assertThat(session.isUsedForDiary(DIARY_ID)).isTrue();
        assertThat(session.isUsedForDiary(ANOTHER_DIARY_ID)).isFalse();
    }

    @Test
    @DisplayName("이미 사용한 게스트 세션은 다시 사용할 수 없다")
    void rejectsReusingGuestSession() {
        GuestSession session = createSession();
        session.useForDiary(DIARY_ID, USED_AT);

        assertThatThrownBy(() -> session.useForDiary(
                ANOTHER_DIARY_ID,
                USED_AT.plusSeconds(1)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이미 사용한 게스트 세션은 다시 사용할 수 없습니다.");

        assertThat(session.getDiaryId()).isEqualTo(DIARY_ID);
        assertThat(session.getUsedAt()).isEqualTo(USED_AT);
        assertThat(session.getUpdatedAt()).isEqualTo(USED_AT);
    }

    @Test
    @DisplayName("만료된 게스트 세션은 사용할 수 없다")
    void rejectsUsingExpiredGuestSession() {
        GuestSession session = createSession();

        assertThatThrownBy(() -> session.useForDiary(DIARY_ID, EXPIRES_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("만료된 게스트 세션은 사용할 수 없습니다.");

        assertThat(session.isUsed()).isFalse();
        assertThat(session.getDiaryId()).isNull();
        assertThat(session.getUsedAt()).isNull();
    }

    @Test
    @DisplayName("게스트 세션 사용 시 일기 ID가 필요하다")
    void rejectsUsingWithoutDiaryId() {
        GuestSession session = createSession();

        assertThatThrownBy(() -> session.useForDiary(null, USED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("일기 ID는 필수입니다.");
    }

    @Test
    @DisplayName("게스트 세션 사용 시 사용 시각이 필요하다")
    void rejectsUsingWithoutUsedAt() {
        GuestSession session = createSession();

        assertThatThrownBy(() -> session.useForDiary(DIARY_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("사용 시각은 필수입니다.");
    }

    @Test
    @DisplayName("게스트 세션을 생성 시각 이전에 사용할 수 없다")
    void rejectsUsageBeforeCreation() {
        GuestSession session = createSession();
        Instant beforeCreation = CREATED_AT.minusSeconds(1);

        assertThatThrownBy(() -> session.useForDiary(DIARY_ID, beforeCreation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용 시각은 생성 시각 이전일 수 없습니다.");
    }

    @Test
    @DisplayName("게스트 사용자 ID가 없으면 세션을 생성할 수 없다")
    void rejectsMissingGuestUserId() {
        assertThatThrownBy(() -> GuestSession.create(
                null,
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("게스트 사용자 ID는 필수입니다.");
    }

    @Test
    @DisplayName("토큰 해시가 없으면 세션을 생성할 수 없다")
    void rejectsMissingTokenHash() {
        assertThatThrownBy(() -> GuestSession.create(
                GUEST_USER_ID,
                null,
                EXPIRES_AT,
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("토큰 해시는 64자리 소문자 16진수여야 합니다.");
    }

    @Test
    @DisplayName("토큰 해시는 64자리여야 한다")
    void rejectsWrongTokenHashLength() {
        assertThatThrownBy(() -> GuestSession.create(
                GUEST_USER_ID,
                "a".repeat(63),
                EXPIRES_AT,
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("토큰 해시는 64자리 소문자 16진수여야 합니다.");

        assertThatThrownBy(() -> GuestSession.create(
                GUEST_USER_ID,
                "a".repeat(65),
                EXPIRES_AT,
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("토큰 해시는 64자리 소문자 16진수여야 합니다.");
    }

    @Test
    @DisplayName("토큰 해시는 소문자 16진수 형식이어야 한다")
    void rejectsInvalidTokenHashFormat() {
        String uppercaseHash = "A".repeat(64);
        String nonHexHash = "z".repeat(64);

        assertThatThrownBy(() -> GuestSession.create(
                GUEST_USER_ID,
                uppercaseHash,
                EXPIRES_AT,
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("토큰 해시는 64자리 소문자 16진수여야 합니다.");

        assertThatThrownBy(() -> GuestSession.create(
                GUEST_USER_ID,
                nonHexHash,
                EXPIRES_AT,
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("토큰 해시는 64자리 소문자 16진수여야 합니다.");
    }

    @Test
    @DisplayName("만료 시각은 생성 시각 이후여야 한다")
    void rejectsInvalidExpiration() {
        assertThatThrownBy(() -> GuestSession.create(
                GUEST_USER_ID,
                TOKEN_HASH,
                CREATED_AT,
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("만료 시각은 생성 시각 이후여야 합니다.");

        Instant beforeCreatedAt =
                Instant.parse("2026-08-18T23:59:59Z");

        assertThatThrownBy(() -> GuestSession.create(
                GUEST_USER_ID,
                TOKEN_HASH,
                beforeCreatedAt,
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("만료 시각은 생성 시각 이후여야 합니다.");
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
