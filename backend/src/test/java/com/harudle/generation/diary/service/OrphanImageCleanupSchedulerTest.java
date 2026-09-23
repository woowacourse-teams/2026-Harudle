package com.harudle.generation.diary.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.config.OrphanImageCleanupProperties;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.port.GeneratedImageCatalog;
import com.harudle.generation.diary.service.port.GeneratedImageCatalog.Image;
import com.harudle.generation.diary.service.port.GeneratedImageCatalog.Page;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrphanImageCleanupSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final UUID ID = UUID.randomUUID();
    private static final String KEY = "generated/" + ID + "/" + UUID.randomUUID() + "/image.png";
    private static final Image OLD = new Image(ID, KEY, NOW.minus(Duration.ofHours(2)));
    @Mock private GeneratedImageCatalog catalog;
    @Mock private ImageStorage storage;
    @Mock private DiaryGenerationRepository generations;
    @Mock private GenerationPromptRepository prompts;
    private OrphanImageCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OrphanImageCleanupScheduler(catalog, storage, generations, prompts,
                new OrphanImageCleanupProperties(Duration.ofHours(1), Duration.ofMinutes(10)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void protectsProcessingAndAdoptedImagesButDeletesUnusedAttempts() {
        String unusedKey = KEY + ".unused";
        UUID processingId = UUID.randomUUID();
        when(catalog.list(null)).thenReturn(new Page(List.of(OLD,
                new Image(ID, unusedKey, OLD.lastModified()),
                new Image(processingId, "processing", OLD.lastModified())), null));
        DiaryGeneration succeeded = mock(DiaryGeneration.class);
        when(succeeded.getStatus()).thenReturn(GenerationStatus.SUCCEEDED);
        when(succeeded.notUsesImageObjectKey(KEY)).thenReturn(false);
        when(succeeded.notUsesImageObjectKey(unusedKey)).thenReturn(true);
        when(generations.findById(ID)).thenReturn(Optional.of(succeeded));
        DiaryGeneration processing = mock(DiaryGeneration.class);
        when(processing.getStatus()).thenReturn(GenerationStatus.PROCESSING);
        when(generations.findById(processingId)).thenReturn(Optional.of(processing));

        scheduler.cleanup();

        verify(storage).delete(unusedKey);
        verify(storage, never()).delete(KEY);
        verify(storage, never()).delete("processing");
    }

    @Test
    void skipsYoungImagesAndVisitsFollowingPages() {
        when(catalog.list(null)).thenReturn(new Page(
                List.of(new Image(ID, "young", NOW)), "next"));
        when(catalog.list("next")).thenReturn(new Page(List.of(OLD), null));
        scheduler.cleanup();
        verify(storage).delete(KEY);
        verify(storage, never()).delete("young");
        verify(generations).findById(ID);
    }

    @Test
    void deletesFailedGenerationImage() {
        when(catalog.list(null)).thenReturn(new Page(List.of(OLD), null));
        DiaryGeneration failed = mock(DiaryGeneration.class);
        when(failed.getStatus()).thenReturn(GenerationStatus.FAILED);
        when(generations.findById(ID)).thenReturn(Optional.of(failed));
        scheduler.cleanup();
        verify(storage).delete(KEY);
    }

    @Test
    void protectsReferencesFromOtherGenerationsAndPrompts() {
        when(catalog.list(null)).thenReturn(new Page(List.of(OLD), null));
        when(generations.existsByImageObjectKey(KEY)).thenReturn(true, false);
        scheduler.cleanup();
        when(prompts.existsByImageAssetObjectKey(KEY)).thenReturn(true);
        scheduler.cleanup();
        verifyNoInteractions(storage);
    }

    @Test
    void defersDatabaseFailureAndContinuesNextObject() {
        UUID otherId = UUID.randomUUID();
        when(catalog.list(null)).thenReturn(new Page(List.of(OLD,
                new Image(otherId, "other", OLD.lastModified())), null));
        when(generations.findById(ID)).thenThrow(new IllegalStateException("DB unavailable"));
        scheduler.cleanup();
        verify(storage, never()).delete(KEY);
        verify(storage).delete("other");
    }

    @Test
    void retriesFailedDeleteOnNextSweep() {
        when(catalog.list(null)).thenReturn(new Page(List.of(OLD), null));
        doThrow(new IllegalStateException("S3 unavailable")).doNothing().when(storage).delete(KEY);
        scheduler.cleanup();
        scheduler.cleanup();
        verify(storage, times(2)).delete(KEY);
    }

    @Test
    void rediscoversLatePutEvenAfterSuccessfulDeleteAndEmptySweep() {
        when(catalog.list(null)).thenReturn(new Page(List.of(OLD), null),
                new Page(List.of(), null), new Page(List.of(OLD), null));
        scheduler.cleanup();
        scheduler.cleanup();
        scheduler.cleanup();
        verify(storage, times(2)).delete(KEY);
        verify(catalog, times(3)).list(null);
    }

    @Test
    void restartsFromBeginningAfterListingFailure() {
        when(catalog.list(null)).thenThrow(new IllegalStateException("S3 unavailable"))
                .thenReturn(new Page(List.of(OLD), null));
        scheduler.cleanup();
        scheduler.cleanup();
        verify(storage).delete(KEY);
    }
}
