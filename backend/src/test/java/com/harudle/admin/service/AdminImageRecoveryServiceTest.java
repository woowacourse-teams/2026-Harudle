package com.harudle.admin.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.harudle.generation.diary.domain.*;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.prompt.domain.GenerationPrompt;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import com.harudle.generation.diary.service.port.*;
import com.harudle.generation.diary.service.port.dto.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

class AdminImageRecoveryServiceTest {
    private final DiaryGenerationRepository generations = mock(DiaryGenerationRepository.class);
    private final GenerationPromptRepository prompts = mock(GenerationPromptRepository.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final DiaryImageGenerator generator = mock(DiaryImageGenerator.class);
    private final DiaryGeneration generation = DiaryGeneration.start(UUID.randomUUID(), 1L,
            UUID.randomUUID(), "a".repeat(64));
    private final Storyboard storyboard = new Storyboard("하루", "같은 주인공", List.of(
            panel(1), panel(2), panel(3), panel(4)));
    private AdminImageRecoveryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<ImageStorage> storages = mock(ObjectProvider.class);
        ObjectProvider<DiaryImageGenerator> generators = mock(ObjectProvider.class);
        when(storages.getIfAvailable()).thenReturn(storage);
        when(generators.getIfAvailable()).thenReturn(generator);
        service = new AdminImageRecoveryService(generations, prompts, generators, storages);
        generation.succeed(storyboard, "generated/original/image.png", Instant.EPOCH);
        when(generations.findById(generation.getId())).thenReturn(Optional.of(generation));
    }

    @Test
    void restoresSavedStoryboardUsingLatestStyleAndReference() {
        var reference = new ReferenceImage(new ByteArrayResource(new byte[]{1}), MediaType.IMAGE_PNG);
        var image = new GeneratedImage(new ByteArrayResource(new byte[]{2}), MediaType.IMAGE_PNG);
        when(prompts.findFirstByOrderByIdDesc()).thenReturn(Optional.of(new GenerationPrompt("story", "latest-style", "references/latest.png")));
        when(storage.load("references/latest.png")).thenReturn(reference);
        when(generator.generate(new DiaryImageGenerationRequest(storyboard, "latest-style", reference))).thenReturn(image);
        when(storage.restoreIfMissing(generation.getImageObjectKey(), image)).thenReturn(true);
        assertThat(service.restore(generation.getId()).status()).isEqualTo("RESTORED");
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(generation.getCompletedAt()).isEqualTo(Instant.EPOCH);
        assertThat(generation.getImageObjectKey()).isEqualTo("generated/original/image.png");
        verify(generations).findById(generation.getId());
        verifyNoMoreInteractions(generations);
        verify(prompts).findFirstByOrderByIdDesc();
        verify(prompts, never()).findById(anyLong());
        verify(storage).load("references/latest.png");
        verify(generator).generate(new DiaryImageGenerationRequest(storyboard, "latest-style", reference));
        verify(storage, never()).delete(anyString());
        verify(storage, never()).store(any(), any());
    }

    @Test
    void skipsExistingImageWithoutCallingGenerator() {
        when(storage.exists(generation.getImageObjectKey())).thenReturn(true);
        assertThat(service.restore(generation.getId()).status()).isEqualTo("ALREADY_EXISTS");
        verifyNoInteractions(generator, prompts);
    }

    @Test
    void refusesProcessingGeneration() {
        var processing = DiaryGeneration.start(UUID.randomUUID(), 1L, UUID.randomUUID(), "b".repeat(64));
        when(generations.findById(processing.getId())).thenReturn(Optional.of(processing));
        assertThatThrownBy(() -> service.restore(processing.getId())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(storage, generator);
    }

    @Test
    void refusesWrongFormatBeforeUpload() {
        var reference = new ReferenceImage(new ByteArrayResource(new byte[]{1}), MediaType.IMAGE_PNG);
        when(prompts.findFirstByOrderByIdDesc()).thenReturn(Optional.of(new GenerationPrompt("story", "latest-style", "references/latest.png")));
        when(storage.load("references/latest.png")).thenReturn(reference);
        when(generator.generate(any())).thenReturn(new GeneratedImage(new ByteArrayResource(new byte[]{2}), MediaType.IMAGE_JPEG));
        assertThatThrownBy(() -> service.restore(generation.getId())).isInstanceOf(ResponseStatusException.class);
        verify(storage, never()).restoreIfMissing(anyString(), any());
    }

    @Test
    void missingReferenceStopsBeforeGeneration() {
        when(prompts.findFirstByOrderByIdDesc()).thenReturn(Optional.of(new GenerationPrompt("story", "latest-style", "references/latest.png")));
        when(storage.load("references/latest.png")).thenThrow(new ImageStorageException("missing reference"));
        assertThatThrownBy(() -> service.restore(generation.getId())).isInstanceOf(ImageStorageException.class);
        verifyNoInteractions(generator);
        verify(storage, never()).restoreIfMissing(anyString(), any());
    }

    @Test
    void lookupFailureDoesNotTriggerGeneration() {
        when(storage.exists(generation.getImageObjectKey())).thenThrow(new ImageStorageException("access denied"));
        assertThatThrownBy(() -> service.restore(generation.getId())).isInstanceOf(ImageStorageException.class);
        verifyNoInteractions(generator, prompts);
    }

    @Test
    void uploadsProvidedImageToOriginalKeyWithoutAi() throws Exception {
        when(generations.findFirstByImageObjectKey(generation.getImageObjectKey())).thenReturn(Optional.of(generation));
        byte[] bytes = png();
        when(storage.restoreIfMissing(eq(generation.getImageObjectKey()), any())).thenReturn(true);
        assertThat(service.upload(generation.getImageObjectKey(), bytes).status()).isEqualTo("RESTORED");
        var image = org.mockito.ArgumentCaptor.forClass(GeneratedImage.class);
        verify(storage).restoreIfMissing(eq("generated/original/image.png"), image.capture());
        assertThat(image.getValue().resource().getContentAsByteArray()).isEqualTo(bytes);
        assertThat(image.getValue().mediaType()).isEqualTo(MediaType.IMAGE_PNG);
        verifyNoInteractions(generator, prompts);
        verify(generations).findFirstByImageObjectKey(generation.getImageObjectKey());
        verifyNoMoreInteractions(generations);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void uploadSkipsExistingObject() throws Exception {
        when(generations.findFirstByImageObjectKey(generation.getImageObjectKey())).thenReturn(Optional.of(generation));
        when(storage.exists(generation.getImageObjectKey())).thenReturn(true);
        assertThat(service.upload(generation.getImageObjectKey(), png()).status()).isEqualTo("ALREADY_EXISTS");
        verify(storage, never()).restoreIfMissing(anyString(), any());
        verifyNoInteractions(generator, prompts);
    }

    @Test
    void uploadRejectsNonImageBytes() {
        when(generations.findFirstByImageObjectKey(generation.getImageObjectKey())).thenReturn(Optional.of(generation));
        assertThatThrownBy(() -> service.upload(generation.getImageObjectKey(), new byte[]{1, 2, 3}))
                .isInstanceOf(ResponseStatusException.class);
        verify(storage, never()).restoreIfMissing(anyString(), any());
        verifyNoInteractions(generator, prompts);
    }

    @Test
    void uploadRejectsFormatMismatch() throws Exception {
        var jpegGeneration = DiaryGeneration.start(UUID.randomUUID(), 1L, UUID.randomUUID(), "c".repeat(64));
        jpegGeneration.succeed(storyboard, "generated/original/image.jpg", Instant.EPOCH);
        when(generations.findFirstByImageObjectKey(jpegGeneration.getImageObjectKey())).thenReturn(Optional.of(jpegGeneration));
        byte[] bytes = png();
        assertThatThrownBy(() -> service.upload(jpegGeneration.getImageObjectKey(), bytes))
                .isInstanceOf(ResponseStatusException.class);
        verify(storage, never()).restoreIfMissing(anyString(), any());
    }

    @Test
    void uploadRejectsUnknownKeyBeforeAccessingStorage() {
        when(generations.findFirstByImageObjectKey("unknown/image.png")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upload("unknown/image.png", new byte[]{1}))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(storage, generator, prompts);
    }

    @Test
    void uploadRejectsBlankKey() {
        assertThatThrownBy(() -> service.upload(" ", new byte[]{1}))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(storage, generator, prompts);
    }

    private static byte[] png() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private static StoryPanel panel(int number) {
        return new StoryPanel(number, "장면 " + number, "공원", "주인공", "기쁨", List.of());
    }
}
