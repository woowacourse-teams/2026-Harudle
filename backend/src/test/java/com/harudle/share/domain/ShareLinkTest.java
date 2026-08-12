package com.harudle.share.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShareLinkTest {

    @Test
    @DisplayName("공유 링크를 생성하면 고유한 ID가 생성된다")
    void createWithId() {
        UUID generationId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-11T03:00:00Z");

        ShareLink shareLink = ShareLink.create(generationId, createdAt);

        assertThat(shareLink.getId()).isNotNull();
    }

    @Test
    @DisplayName("그림일기 생성 ID로 공유 링크를 생성한다")
    void createWithGenerationId() {
        UUID generationId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-11T03:00:00Z");

        ShareLink shareLink = ShareLink.create(generationId, createdAt);

        assertThat(shareLink.getGenerationId()).isEqualTo(generationId);
    }

    @Test
    @DisplayName("공유 링크를 생성하면 생성 일시가 기록된다")
    void recordCreatedAt() {
        UUID generationId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-11T03:00:00Z");

        ShareLink shareLink = ShareLink.create(generationId, createdAt);

        assertThat(shareLink.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("그림일기 생성 ID가 없으면 공유 링크를 생성할 수 없다")
    void failToCreateWithoutGenerationId() {
        Instant createdAt = Instant.parse("2026-08-11T03:00:00Z");

        assertThatThrownBy(() -> ShareLink.create(null, createdAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("생성 ID는 필수입니다.");
    }
}
