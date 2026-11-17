package com.harudle.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.admin.query.AdminPostcardImage;
import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.exception.DiaryGenerationNotFoundException;
import com.harudle.generation.diary.service.port.ImageStorage;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AdminPostcardImageServiceTest {

    private static final UUID GENERATION_ID = UUID.randomUUID();
    private static final UUID DIARY_ID = UUID.randomUUID();
    private static final String DETAIL_KEY = "harudle/generated/diary-images/dev/2026/11/17/abc/image-960.webp";
    private static final String ORIGINAL_PNG_KEY = "harudle/generated/diary-images/dev/2026/11/17/abc/image.png";
    private static final String ORIGINAL_JPG_KEY = "harudle/generated/diary-images/dev/2026/11/17/abc/image.jpg";

    private final DiaryGenerationRepository generations = mock(DiaryGenerationRepository.class);
    private final DiaryRepository diaries = mock(DiaryRepository.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ImageStorage> storages = mock(ObjectProvider.class);
    private final DiaryGeneration generation = mock(DiaryGeneration.class);
    private final Diary diary = mock(Diary.class);

    private AdminPostcardImageService service;

    @BeforeEach
    void setUp() {
        service = new AdminPostcardImageService(generations, diaries, storages);
        when(storages.getIfAvailable()).thenReturn(storage);
        when(generations.findById(GENERATION_ID)).thenReturn(Optional.of(generation));
        when(generation.getStatus()).thenReturn(GenerationStatus.SUCCEEDED);
        when(generation.getImageObjectKey()).thenReturn(DETAIL_KEY);
        when(generation.getDiaryId()).thenReturn(DIARY_ID);
        when(generation.getTitle()).thenReturn("콘티는 천사다");
        when(diaries.findById(DIARY_ID)).thenReturn(Optional.of(diary));
        when(diary.isDeleted()).thenReturn(false);
    }

    @Test
    @DisplayName("인쇄 화질을 위해 저장소에 남아 있는 원본 이미지를 고른다")
    void prefersStoredOriginalImage() {
        when(storage.exists(ORIGINAL_PNG_KEY)).thenReturn(false);
        when(storage.exists(ORIGINAL_JPG_KEY)).thenReturn(true);

        AdminPostcardImage image = service.find(GENERATION_ID);

        assertThat(image.imageObjectKey()).isEqualTo(ORIGINAL_JPG_KEY);
        assertThat(image.title()).isEqualTo("콘티는 천사다");
    }

    @Test
    @DisplayName("원본이 없거나 저장소 설정이 없으면 상세 이미지를 쓴다")
    void fallsBackToDetailImage() {
        assertThat(service.find(GENERATION_ID).imageObjectKey()).isEqualTo(DETAIL_KEY);

        when(storages.getIfAvailable()).thenReturn(null);
        assertThat(service.find(GENERATION_ID).imageObjectKey()).isEqualTo(DETAIL_KEY);
    }

    @Test
    @DisplayName("성공하지 않은 생성은 엽서로 만들 수 없어 없는 생성으로 다룬다")
    void rejectsUnfinishedGeneration() {
        when(generation.getStatus()).thenReturn(GenerationStatus.FAILED);

        assertThatThrownBy(() -> service.find(GENERATION_ID))
                .isInstanceOf(DiaryGenerationNotFoundException.class);
        verify(storages, never()).getIfAvailable();
    }

    @Test
    @DisplayName("일기가 삭제된 생성은 이미지 주소를 발급하지 않는다")
    void rejectsDeletedDiary() {
        when(diary.isDeleted()).thenReturn(true);

        assertThatThrownBy(() -> service.find(GENERATION_ID))
                .isInstanceOf(DiaryGenerationNotFoundException.class);
        verify(storages, never()).getIfAvailable();
    }

    @Test
    @DisplayName("없는 생성 ID는 생성 없음으로 알린다")
    void rejectsUnknownGeneration() {
        UUID unknown = UUID.randomUUID();
        when(generations.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.find(unknown))
                .isInstanceOf(DiaryGenerationNotFoundException.class);
    }
}
