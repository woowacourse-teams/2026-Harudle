package com.harudle.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DatabaseLockPropertiesTest {
    @Test
    @DisplayName("설정이 없으면 잠금 대기 상한은 1초다")
    void defaultsToOneSecond() {
        assertThat(new DatabaseLockProperties(null).interactionLockTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("제한 비활성화나 PostgreSQL 범위를 넘는 설정은 시작 단계에서 거절한다")
    void rejectsDisabledOrOutOfRangeTimeout() {
        for (Duration value : new Duration[]{Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1),
                Duration.ofMillis((long) Integer.MAX_VALUE + 1)}) {
            assertThatThrownBy(() -> new DatabaseLockProperties(value)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
