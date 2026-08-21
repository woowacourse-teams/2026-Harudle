package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.when;

import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.repository.DiarySnapshot;
import com.harudle.diary.service.dto.DiaryDayResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryTimelineResult;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.repository.DiaryGenerationRepository;
import com.harudle.generation.repository.DiaryGenerationSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
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
    private static final UUID DIARY_ID = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID SECOND_DIARY_ID = UUID.fromString("8c82a1c2-993f-41e9-8464-a8554b7620d7");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final UUID SECOND_GENERATION_ID = UUID.fromString("a8e8758d-493b-42be-9127-c5a457ee5f12");
    private static final LocalDate DIARY_DATE = LocalDate.of(2028, 2, 6);
    private static final Instant CREATED_AT = Instant.parse("2028-02-06T11:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2028-02-06T12:00:00Z");

    @Mock
    private DiaryRepository diaryRepository;

    @Mock
    private DiaryGenerationRepository diaryGenerationRepository;

    private DiaryQueryService diaryQueryService;

    @BeforeEach
    void setUp() {
        diaryQueryService = new DiaryQueryService(diaryRepository, diaryGenerationRepository);
    }

    @Test
    @DisplayName("윤년의 모든 날짜와 성공한 일기를 최신순 월간 타임라인으로 조회한다")
    void getTimelineIncludesEveryDayOfLeapMonth() {
        DiarySnapshot diary = createDiarySnapshot(DIARY_ID, USER_ID);
        DiarySnapshot secondDiary = createDiarySnapshot(SECOND_DIARY_ID, USER_ID);
        DiaryGenerationSnapshot generation = createSuccessfulGenerationSnapshot(
                GENERATION_ID,
                DIARY_ID,
                "친구와 보낸 하루",
                "generated/comic.png"
        );
        DiaryGenerationSnapshot secondGeneration = createSuccessfulGenerationSnapshot(
                SECOND_GENERATION_ID,
                SECOND_DIARY_ID,
                "산책으로 마무리한 하루",
                "generated/second-comic.png"
        );
        when(diaryRepository.findMonthlySnapshots(
                USER_ID,
                LocalDate.of(2028, 2, 1),
                LocalDate.of(2028, 2, 29)
        )).thenReturn(List.of(secondDiary, diary));
        when(diaryGenerationRepository.findSnapshotsByDiaryIdInAndStatus(
                List.of(SECOND_DIARY_ID, DIARY_ID),
                GenerationStatus.SUCCEEDED
        )).thenReturn(List.of(generation, secondGeneration));

        DiaryTimelineResult result = diaryQueryService.getTimeline(USER_ID, 2028, 2);

        assertThat(result.days()).hasSize(29);
        assertThat(result.days())
                .extracting(DiaryDayResult::date)
                .isSortedAccordingTo(Comparator.reverseOrder());
        DiaryDayResult diaryDay = result.days().stream()
                .filter(day -> day.date().equals(DIARY_DATE))
                .findFirst()
                .orElseThrow();
        assertThat(diaryDay.hasItems()).isTrue();
        assertThat(diaryDay.items())
                .extracting(
                        item -> item.id(),
                        item -> item.title(),
                        item -> item.imageObjectKey()
                )
                .containsExactly(
                        tuple(
                                SECOND_DIARY_ID,
                                "산책으로 마무리한 하루",
                                "generated/second-comic.png"
                        ),
                        tuple(
                                DIARY_ID,
                                "친구와 보낸 하루",
                                "generated/comic.png"
                        )
                );
        assertThat(result.days().getFirst().date()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(result.days().getLast().date()).isEqualTo(LocalDate.of(2028, 2, 1));
    }

    @Test
    @DisplayName("본인 소유의 삭제되지 않은 일기 상세를 조회한다")
    void getDetail() {
        when(diaryRepository.findActiveSnapshotById(DIARY_ID))
                .thenReturn(Optional.of(createDiarySnapshot(DIARY_ID, USER_ID)));
        when(diaryGenerationRepository.findSnapshotByDiaryId(DIARY_ID))
                .thenReturn(Optional.of(createSuccessfulGenerationSnapshot(
                        GENERATION_ID,
                        DIARY_ID,
                        "친구와 보낸 하루",
                        "generated/comic.png"
                )));

        DiaryDetailResult result = diaryQueryService.getDetail(USER_ID, DIARY_ID);

        assertThat(result.id()).isEqualTo(DIARY_ID);
        assertThat(result.sourceText()).isEqualTo("오늘의 일기");
        assertThat(result.generation().title()).isEqualTo("친구와 보낸 하루");
    }

    @Test
    @DisplayName("삭제됐거나 존재하지 않는 일기는 상세 조회할 수 없다")
    void getDetailRejectsInactiveDiary() {
        when(diaryRepository.findActiveSnapshotById(DIARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryQueryService.getDetail(USER_ID, DIARY_ID))
                .isInstanceOf(DiaryNotFoundException.class);
    }

    @Test
    @DisplayName("다른 사용자의 일기는 상세 조회할 수 없다")
    void getDetailRejectsOtherUsersDiary() {
        when(diaryRepository.findActiveSnapshotById(DIARY_ID))
                .thenReturn(Optional.of(createDiarySnapshot(DIARY_ID, OTHER_USER_ID)));

        assertThatThrownBy(() -> diaryQueryService.getDetail(USER_ID, DIARY_ID))
                .isInstanceOf(DiaryAccessDeniedException.class);
    }

    private DiarySnapshot createDiarySnapshot(UUID diaryId, UUID userId) {
        return new DiarySnapshot(
                diaryId,
                userId,
                DIARY_DATE,
                "오늘의 일기",
                CREATED_AT
        );
    }

    private DiaryGenerationSnapshot createSuccessfulGenerationSnapshot(
            UUID generationId,
            UUID diaryId,
            String title,
            String imageObjectKey
    ) {
        return new DiaryGenerationSnapshot(
                generationId,
                diaryId,
                GenerationStatus.SUCCEEDED,
                title,
                imageObjectKey,
                COMPLETED_AT
        );
    }
}
