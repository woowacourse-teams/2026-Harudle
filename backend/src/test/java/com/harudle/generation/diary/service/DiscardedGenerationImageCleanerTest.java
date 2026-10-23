package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.port.ImageStorage;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DiscardedGenerationImageCleanerTest {

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"PENDING", "PROCESSING"})
    @DisplayName("대기 중이거나 처리 중인 작업의 이미지는 완료 오류가 나도 삭제하지 않는다")
    void retainImageWhileGenerationIsUnfinished(GenerationStatus status) {
        UUID generationId = UUID.randomUUID();
        String imageObjectKey = "generated/diary.png";
        DiaryGeneration generation = mock(DiaryGeneration.class);
        when(generation.getStatus()).thenReturn(status);
        DiaryGenerationRepository repository = mock(DiaryGenerationRepository.class);
        when(repository.findById(generationId)).thenReturn(Optional.of(generation));
        ImageStorage storage = mock(ImageStorage.class);
        DiscardedGenerationImageCleaner cleaner = new DiscardedGenerationImageCleaner(repository, storage);

        cleaner.deleteIfSafelyDiscardable(generationId, imageObjectKey, new IllegalStateException("완료 처리 실패"));

        verify(storage, never()).delete(imageObjectKey);
    }

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
