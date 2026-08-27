package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.repository.DiarySnapshot;
import com.harudle.diary.service.dto.DiaryDayResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryStreakDayResult;
import com.harudle.diary.service.dto.DiaryStreakResult;
import com.harudle.diary.service.dto.DiaryTimelineResult;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.repository.DiaryGenerationRepository;
import com.harudle.generation.repository.DiaryGenerationSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private static final Instant CURRENT_TIME = Instant.parse("2028-02-05T15:30:00Z");
    private static final Instant CREATED_AT = Instant.parse("2028-02-06T11:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2028-02-06T12:00:00Z");
    private static final ZoneId SERVICE_ZONE_ID = ZoneId.of("Asia/Seoul");

    @Mock
    private DiaryRepository diaryRepository;

    @Mock
    private DiaryGenerationRepository diaryGenerationRepository;

    private DiaryQueryService diaryQueryService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(CURRENT_TIME, SERVICE_ZONE_ID);
        diaryQueryService = new DiaryQueryService(
                diaryRepository,
                diaryGenerationRepository,
                clock
        );
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
        when(diaryRepository.findActiveSnapshotsBetween(
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
    @DisplayName("삭제된 중간 일기의 날짜는 streak에 유지하고 콘텐츠만 제외한다")
    void getCurrentStreakKeepsDeletedDayWithoutExposingContent() {
        LocalDate deletedDate = DIARY_DATE.minusDays(1);
        LocalDate oldestDate = DIARY_DATE.minusDays(2);
        DiarySnapshot todayDiary = createDiarySnapshot(DIARY_ID, USER_ID, DIARY_DATE);
        DiarySnapshot oldestDiary = createDiarySnapshot(SECOND_DIARY_ID, USER_ID, oldestDate);
        DiaryGenerationSnapshot todayGeneration = createSuccessfulGenerationSnapshot(
                GENERATION_ID,
                DIARY_ID,
                "오늘의 일기",
                "generated/today.png"
        );
        DiaryGenerationSnapshot oldestGeneration = createSuccessfulGenerationSnapshot(
                SECOND_GENERATION_ID,
                SECOND_DIARY_ID,
                "그제의 일기",
                "generated/oldest.png"
        );
        when(diaryRepository.findDiaryDatesIncludingDeletedByGenerationStatus(
                USER_ID,
                DIARY_DATE,
                GenerationStatus.SUCCEEDED
        )).thenReturn(List.of(DIARY_DATE, deletedDate, oldestDate));
        when(diaryRepository.findActiveSnapshotsBetween(
                USER_ID,
                oldestDate,
                DIARY_DATE
        )).thenReturn(List.of(todayDiary, oldestDiary));
        when(diaryGenerationRepository.findSnapshotsByDiaryIdInAndStatus(
                List.of(DIARY_ID, SECOND_DIARY_ID),
                GenerationStatus.SUCCEEDED
        )).thenReturn(List.of(todayGeneration, oldestGeneration));

        DiaryStreakResult result = diaryQueryService.getCurrentStreak(USER_ID);

        assertThat(result.streakCount()).isEqualTo(3);
        assertThat(result.recordedToday()).isTrue();
        assertThat(result.days())
                .extracting(DiaryStreakDayResult::date)
                .containsExactly(DIARY_DATE, deletedDate, oldestDate);
        assertThat(result.days().getFirst().items())
                .extracting(item -> item.id())
                .containsExactly(DIARY_ID);
        assertThat(result.days().get(1).items()).isEmpty();
        assertThat(result.days().getLast().items())
                .extracting(item -> item.id())
                .containsExactly(SECOND_DIARY_ID);
    }

    @Test
    @DisplayName("오늘 기록이 없으면 어제까지의 streak와 미기록 상태를 반환한다")
    void getCurrentStreakFromYesterday() {
        LocalDate yesterday = DIARY_DATE.minusDays(1);
        LocalDate oldestDate = DIARY_DATE.minusDays(2);
        when(diaryRepository.findDiaryDatesIncludingDeletedByGenerationStatus(
                USER_ID,
                DIARY_DATE,
                GenerationStatus.SUCCEEDED
        )).thenReturn(List.of(yesterday, oldestDate));
        when(diaryRepository.findActiveSnapshotsBetween(
                USER_ID,
                oldestDate,
                yesterday
        )).thenReturn(List.of());

        DiaryStreakResult result = diaryQueryService.getCurrentStreak(USER_ID);

        assertThat(result.streakCount()).isEqualTo(2);
        assertThat(result.recordedToday()).isFalse();
        assertThat(result.days())
                .extracting(DiaryStreakDayResult::date)
                .containsExactly(yesterday, oldestDate);
        assertThat(result.days())
                .allSatisfy(day -> assertThat(day.items()).isEmpty());
        verifyNoInteractions(diaryGenerationRepository);
    }

    @Test
    @DisplayName("현재 streak가 끝났으면 콘텐츠를 추가 조회하지 않는다")
    void getCurrentStreakReturnsEmptyWhenExpired() {
        when(diaryRepository.findDiaryDatesIncludingDeletedByGenerationStatus(
                USER_ID,
                DIARY_DATE,
                GenerationStatus.SUCCEEDED
        )).thenReturn(List.of(DIARY_DATE.minusDays(2)));

        DiaryStreakResult result = diaryQueryService.getCurrentStreak(USER_ID);

        assertThat(result.streakCount()).isZero();
        assertThat(result.recordedToday()).isFalse();
        assertThat(result.days()).isEmpty();
        verifyNoInteractions(diaryGenerationRepository);
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
        return createDiarySnapshot(diaryId, userId, DIARY_DATE);
    }

    private DiarySnapshot createDiarySnapshot(
            UUID diaryId,
            UUID userId,
            LocalDate diaryDate
    ) {
        return new DiarySnapshot(
                diaryId,
                userId,
                diaryDate,
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
