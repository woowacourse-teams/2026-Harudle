package com.harudle.block.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserBlockTest {
    @Test
    @DisplayName("한 방향의 두 회원과 DB에서 보존할 마이크로초 시각을 저장한다")
    void preservesDirectionAndDatabasePrecision() {
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UserBlock block = new UserBlock(actor, target, Instant.parse("2026-10-22T00:00:00.123456789Z"));
        assertThat(block.getId()).isNotNull();
        assertThat(block.getBlockerId()).isEqualTo(actor);
        assertThat(block.getBlockedId()).isEqualTo(target);
        assertThat(block.getCreatedAt()).isEqualTo(Instant.parse("2026-10-22T00:00:00.123456Z"));
    }

    @Test
    @DisplayName("자기 차단과 필수값 누락은 회원 정보를 예외에 싣지 않고 거절한다")
    void rejectsSelfAndMissingValues() {
        UUID actor = UUID.randomUUID();
        Instant now = Instant.now();
        assertThatThrownBy(() -> new UserBlock(actor, actor, now)).isInstanceOf(InvalidBlockException.class)
                .hasNoCause().hasMessageNotContaining(actor.toString());
        assertThatThrownBy(() -> new UserBlock(null, actor, now)).isInstanceOf(InvalidBlockException.class);
        assertThatThrownBy(() -> new UserBlock(actor, null, now)).isInstanceOf(InvalidBlockException.class);
        assertThatThrownBy(() -> new UserBlock(actor, UUID.randomUUID(), null))
                .isInstanceOf(InvalidBlockException.class);
    }
}
