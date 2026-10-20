package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DiarySourceTextBackfillMainTest {

    @Test
    @DisplayName("전환은 기본 100개 행과 한 번의 후보 조회로 실행 범위를 제한한다")
    void defaultToBoundedMigration() {
        assertThat(DiarySourceTextBackfillMain.parse(new String[]{"migrate"}))
                .isEqualTo(new DiarySourceTextBackfillMain.Options("migrate", 100, 1));
        assertThat(DiarySourceTextBackfillMain.parse(new String[]{
                "migrate", "--batch-size", "1000", "--max-batches", "10000"
        })).isEqualTo(new DiarySourceTextBackfillMain.Options("migrate", 1000, 10000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1001", "2147483648", "1.5", "01", "secret-value"})
    @DisplayName("배치 크기의 잘못된 값과 상한 초과를 입력값 노출 없이 거절한다")
    void rejectInvalidLimits(String limit) {
        assertThatThrownBy(() -> DiarySourceTextBackfillMain.parse(new String[]{"migrate", "--batch-size", limit}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid maintenance limit.");
    }

    @Test
    @DisplayName("조회 명령의 쓰기 옵션과 중복 또는 알 수 없는 옵션을 거절한다")
    void rejectAmbiguousOrUnsupportedOperations() {
        for (String[] arguments : new String[][]{
                {}, {"unsupported"}, {"status", "--batch-size", "1"},
                {"ready", "--max-batches", "1"}, {"verify", "--max-batches", "1"},
                {"finalize", "--max-batches", "1"}, {"migrate", "--batch-size"},
                {"migrate", "--batch-size", "1", "--batch-size", "2"},
                {"migrate", "--unknown", "1"}, {"migrate", "--max-batches", "10001"}
        }) {
            assertThatThrownBy(() -> DiarySourceTextBackfillMain.parse(arguments))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
