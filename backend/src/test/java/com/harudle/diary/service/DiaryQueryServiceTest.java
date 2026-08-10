package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryTimelineResult;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.domain.ComicGeneration;
import com.harudle.generation.domain.StoryPanel;
import com.harudle.generation.domain.Storyboard;
import com.harudle.generation.repository.ComicGenerationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DiaryQueryServiceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID OTHER_USER_ID = UUID.fromString("fcd41d4a-2cce-4f28-bdb7-524d00ef4da6");
    private static final LocalDate DIARY_DATE = LocalDate.of(2028, 2, 6);

    @Mock
    private DiaryRepository diaryRepository;

    @Mock
    private ComicGenerationRepository comicGenerationRepository;

    private DiaryQueryService diaryQueryService;

    @BeforeEach
    void setUp() {
        diaryQueryService = new DiaryQueryService(diaryRepository, comicGenerationRepository);
    }

    @Test
    @DisplayName("윤년의 모든 날짜와 성공한 일기를 월간 타임라인으로 조회한다")
    void getTimelineIncludesEveryDayOfLeapMonth() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "오늘의 일기");
        ComicGeneration generation = createSuccessfulGeneration(diary.getId());
        when(diaryRepository
                .findAllByUserIdAndDiaryDateBetweenAndDeletedAtIsNullOrderByDiaryDateAscCreatedAtAsc(
                        USER_ID,
                        LocalDate.of(2028, 2, 1),
                        LocalDate.of(2028, 2, 29)
                )).thenReturn(List.of(diary));
        when(comicGenerationRepository.findAllByDiaryIdIn(List.of(diary.getId())))
                .thenReturn(List.of(generation));

        DiaryTimelineResult result = diaryQueryService.getTimeline(USER_ID, 2028, 2);

        assertThat(result.days()).hasSize(29);
        assertThat(result.days().get(5).exist()).isTrue();
        assertThat(result.days().get(5).items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo(diary.getId());
                    assertThat(item.title()).isEqualTo("친구와 보낸 하루");
                    assertThat(item.imageObjectKey()).isEqualTo("generated/comic.png");
                });
        assertThat(result.days().getFirst().exist()).isFalse();
    }

    @Test
    @DisplayName("본인 소유의 삭제되지 않은 일기 상세를 조회한다")
    void getDetail() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "오늘의 일기");
        ComicGeneration generation = createSuccessfulGeneration(diary.getId());
        when(diaryRepository.findById(diary.getId())).thenReturn(Optional.of(diary));
        when(comicGenerationRepository.findByDiaryId(diary.getId())).thenReturn(Optional.of(generation));

        DiaryDetailResult result = diaryQueryService.getDetail(USER_ID, diary.getId());

        assertThat(result.id()).isEqualTo(diary.getId());
        assertThat(result.sourceText()).isEqualTo("오늘의 일기");
        assertThat(result.generation().title()).isEqualTo("친구와 보낸 하루");
    }

    @Test
    @DisplayName("삭제된 일기는 상세 조회할 수 없다")
    void getDetailRejectsDeletedDiary() {
        Diary diary = Diary.create(USER_ID, DIARY_DATE, "오늘의 일기");
        diary.delete(Instant.parse("2028-02-06T12:00:00Z"));
        when(diaryRepository.findById(diary.getId())).thenReturn(Optional.of(diary));

        assertThatThrownBy(() -> diaryQueryService.getDetail(USER_ID, diary.getId()))
                .isInstanceOf(DiaryNotFoundException.class);
    }

    @Test
    @DisplayName("다른 사용자의 일기는 상세 조회할 수 없다")
    void getDetailRejectsOtherUsersDiary() {
        Diary diary = Diary.create(OTHER_USER_ID, DIARY_DATE, "오늘의 일기");
        when(diaryRepository.findById(diary.getId())).thenReturn(Optional.of(diary));

        assertThatThrownBy(() -> diaryQueryService.getDetail(USER_ID, diary.getId()))
                .isInstanceOf(DiaryAccessDeniedException.class);
    }

    private ComicGeneration createSuccessfulGeneration(UUID diaryId) {
        ComicGeneration generation = ComicGeneration.start(
                diaryId,
                1L,
                UUID.randomUUID(),
                "a".repeat(64)
        );
        generation.succeed(createStoryboard(), "generated/comic.png", Instant.parse("2028-02-06T12:00:00Z"));
        return generation;
    }

    private Storyboard createStoryboard() {
        return new Storyboard(
                "친구와 보낸 하루",
                "주인공의 옷과 머리 모양을 유지한다.",
                List.of(
                        createPanel(1, "친구를 만났다"),
                        createPanel(2, "카페에 갔다"),
                        createPanel(3, "오래 이야기했다"),
                        createPanel(4, "즐겁게 돌아왔다")
                )
        );
    }

    private StoryPanel createPanel(int panelNumber, String caption) {
        return new StoryPanel(panelNumber, caption, "카페", "친구와 나", "즐거움", List.of());
    }
}
