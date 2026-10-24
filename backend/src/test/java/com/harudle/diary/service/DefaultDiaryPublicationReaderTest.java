package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.diary.service.exception.DiaryNotPublishableException;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.repository.DiaryGenerationSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultDiaryPublicationReaderTest {

    private static final UUID ACTOR = UUID.randomUUID();

    @Mock
    private DiaryRepository diaries;
    @Mock
    private DiaryGenerationRepository generations;
    private DefaultDiaryPublicationReader reader;
    private Diary diary;

    @BeforeEach
    void setUp() {
        reader = new DefaultDiaryPublicationReader(diaries, generations);
        diary = Diary.create(ACTOR, LocalDate.of(2026, 10, 11), "공개하면 안 되는 원문");
    }

    @Test
    void returnsOnlyPublicationInformationForSucceededDiary() {
        when(diaries.findByIdIncludingDeletedForUpdate(diary.getId())).thenReturn(Optional.of(diary));
        when(generations.findSnapshotByDiaryId(diary.getId())).thenReturn(Optional.of(snapshot(GenerationStatus.SUCCEEDED)));
        var result = reader.lockAndRead(ACTOR, diary.getId());
        assertThat(result.diaryId()).isEqualTo(diary.getId());
        assertThat(result.authorId()).isEqualTo(ACTOR);
        assertThat(result.imageObjectKey()).isEqualTo("generated/comic.webp");
    }

    @Test
    void rejectsDeletedDiaryBeforeReadingGeneration() {
        diary.delete(Instant.now());
        when(diaries.findByIdIncludingDeletedForUpdate(diary.getId())).thenReturn(Optional.of(diary));
        assertThatThrownBy(() -> reader.lockAndRead(ACTOR, diary.getId())).isInstanceOf(DiaryNotFoundException.class);
        verifyNoInteractions(generations);
    }

    @Test
    void rejectsOtherOwnersDiaryBeforeReadingGeneration() {
        when(diaries.findByIdIncludingDeletedForUpdate(diary.getId())).thenReturn(Optional.of(diary));
        assertThatThrownBy(() -> reader.lockAndRead(UUID.randomUUID(), diary.getId()))
                .isInstanceOf(DiaryAccessDeniedException.class);
        verifyNoInteractions(generations);
    }

    @Test
    void rejectsMissingDiary() {
        when(diaries.findByIdIncludingDeletedForUpdate(diary.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reader.lockAndRead(ACTOR, diary.getId())).isInstanceOf(DiaryNotFoundException.class);
        verifyNoInteractions(generations);
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"PROCESSING", "FAILED"})
    void rejectsUnfinishedGeneration(GenerationStatus status) {
        when(diaries.findByIdIncludingDeletedForUpdate(diary.getId())).thenReturn(Optional.of(diary));
        when(generations.findSnapshotByDiaryId(diary.getId())).thenReturn(Optional.of(snapshot(status)));
        assertThatThrownBy(() -> reader.lockAndRead(ACTOR, diary.getId()))
                .isInstanceOf(DiaryNotPublishableException.class);
    }

    @Test
    void rejectsMissingGeneration() {
        when(diaries.findByIdIncludingDeletedForUpdate(diary.getId())).thenReturn(Optional.of(diary));
        when(generations.findSnapshotByDiaryId(diary.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reader.lockAndRead(ACTOR, diary.getId()))
                .isInstanceOf(DiaryNotPublishableException.class);
    }

    private DiaryGenerationSnapshot snapshot(GenerationStatus status) {
        return new DiaryGenerationSnapshot(UUID.randomUUID(), diary.getId(), status, "비공개 제목",
                "generated/comic.webp", Instant.now());
    }
}
