package com.harudle.block.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.block.query.BlockCursor;
import com.harudle.block.service.exception.InvalidBlockCursorException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BlockCursorCodecTest {
    private final BlockCursorCodec codec = new BlockCursorCodec();
    private final UUID actor = UUID.randomUUID();

    @Test
    @DisplayName("본인의 마이크로초 경계를 왕복하고 커서가 없으면 첫 페이지를 읽는다")
    void roundTripsOwnedBoundary() {
        BlockCursor cursor = new BlockCursor(actor, Instant.parse("2026-10-22T00:00:00.123456Z"), UUID.randomUUID());
        assertThat(codec.decode(actor, codec.encode(cursor))).contains(cursor);
        assertThat(codec.decode(actor, null)).isEmpty();
        assertThatThrownBy(() -> codec.decode(UUID.randomUUID(), codec.encode(cursor)))
                .isInstanceOf(InvalidBlockCursorException.class).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "!", "=", "a", "abcd=", "not-a-valid-cursor"})
    @DisplayName("잘못된 외부 커서는 원래 값을 예외에 넣지 않고 거절한다")
    void rejectsMalformedCursor(String encoded) {
        assertThatThrownBy(() -> codec.decode(actor, encoded)).isInstanceOf(InvalidBlockCursorException.class)
                .hasNoCause();
    }

    @Test
    @DisplayName("과도한 크기와 다른 종류 및 DB가 보존할 수 없는 정밀도의 경계를 거절한다")
    void rejectsOversizedWrongKindAndPrecision() {
        assertThatThrownBy(() -> codec.decode(actor, "a".repeat(513)))
                .isInstanceOf(InvalidBlockCursorException.class);
        String payload = "1|NOTIFICATIONS|" + actor + "|2026-10-22T00:00:00Z|" + UUID.randomUUID();
        assertThatThrownBy(() -> codec.decode(actor, Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(InvalidBlockCursorException.class);
        BlockCursor nanos = new BlockCursor(actor, Instant.parse("2026-10-22T00:00:00.123456789Z"), UUID.randomUUID());
        assertThatThrownBy(() -> codec.decode(actor, codec.encode(nanos)))
                .isInstanceOf(InvalidBlockCursorException.class);
    }
}
