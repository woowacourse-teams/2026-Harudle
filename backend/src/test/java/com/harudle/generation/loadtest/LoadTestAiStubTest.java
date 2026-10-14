package com.harudle.generation.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.diary.service.exception.AiGenerationException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

class LoadTestAiStubTest {

    @Test
    void interruptionDoesNotReturnSuccessfulGeneration() {
        var reference = new ReferenceImage(new ByteArrayResource(new byte[]{1}), MediaType.IMAGE_PNG);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> new LoadTestAiStub().generateImage(reference))
                    .isInstanceOf(AiGenerationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
