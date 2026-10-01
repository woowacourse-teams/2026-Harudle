package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.port.ImageStorage;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DiscardedGenerationImageCleanerTest {

    @Test
    void auditsDeletionWithoutWritingTheObjectKey(CapturedOutput output) {
        UUID generationId = UUID.randomUUID();
        String imageObjectKey = "generated/private-" + generationId + ".png";
        DiaryGeneration generation = mock(DiaryGeneration.class);
        when(generation.getId()).thenReturn(generationId);
        when(generation.notUsesImageObjectKey(imageObjectKey)).thenReturn(true);
        ImageStorage storage = mock(ImageStorage.class);
        DiscardedGenerationImageCleaner cleaner = new DiscardedGenerationImageCleaner(
                mock(DiaryGenerationRepository.class), storage);

        cleaner.deleteIfUnused(generation, imageObjectKey);

        verify(storage).delete(imageObjectKey);
        assertThat(output)
                .contains("event=discarded_image_deleted")
                .contains("generationId=" + generationId)
                .contains("reason=completed_but_unused")
                .doesNotContain(imageObjectKey);
    }
}
