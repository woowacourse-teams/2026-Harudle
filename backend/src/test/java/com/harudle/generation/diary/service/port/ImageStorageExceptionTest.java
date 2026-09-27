package com.harudle.generation.diary.service.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.diary.service.port.ImageStorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageStorageExceptionTest {

    @Test
    @DisplayName("이미지 저장소 오류 메시지와 원인을 보존한다")
    void createImageStorageException() {
        RuntimeException cause = new RuntimeException("storage failure");

        assertThatThrownBy(() -> {
            throw new ImageStorageException("이미지 저장소 호출에 실패했습니다.", cause);
        }).isInstanceOfSatisfying(
                ImageStorageException.class,
                exception -> {
                    assertThat(exception).hasMessage("이미지 저장소 호출에 실패했습니다.");
                    assertThat(exception).hasCause(cause);
                }
        );
    }

    @Test
    @DisplayName("원인 예외 없이 이미지 저장소 오류를 생성한다")
    void createImageStorageExceptionWithoutCause() {
        assertThatThrownBy(() -> {
            throw new ImageStorageException("이미지 저장에 실패했습니다.");
        }).isInstanceOfSatisfying(
                ImageStorageException.class,
                exception -> {
                    assertThat(exception).hasMessage("이미지 저장에 실패했습니다.");
                    assertThat(exception.getCause()).isNull();
                }
        );
    }

    @Test
    @DisplayName("이미지 저장소 오류 메시지가 비어 있으면 예외를 생성할 수 없다")
    void rejectBlankMessage() {
        assertThatThrownBy(() -> {
            throw new ImageStorageException(" ");
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("오류 메시지");
    }
}
