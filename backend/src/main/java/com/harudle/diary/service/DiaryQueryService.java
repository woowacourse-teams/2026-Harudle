package com.harudle.diary.service;

import com.harudle.diary.repository.DiaryQueryRepository;
import com.harudle.diary.repository.DiarySnapshot;
import com.harudle.diary.service.dto.DiaryDayResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.diary.service.dto.DiarySummaryResult;
import com.harudle.diary.service.dto.DiaryTimelineResult;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.repository.DiaryGenerationQueryRepository;
import com.harudle.generation.repository.DiaryGenerationSnapshot;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DiaryQueryService {

    private final DiaryQueryRepository diaryQueryRepository;
    private final DiaryGenerationQueryRepository diaryGenerationQueryRepository;

    DiaryQueryService(
            DiaryQueryRepository diaryQueryRepository,
            DiaryGenerationQueryRepository diaryGenerationQueryRepository
    ) {
        this.diaryQueryRepository = diaryQueryRepository;
        this.diaryGenerationQueryRepository = diaryGenerationQueryRepository;
    }

    public DiaryTimelineResult getTimeline(UUID userId, int year, int month) {
        validateUserId(userId);
        YearMonth yearMonth = createYearMonth(year, month);
        List<DiarySnapshot> diaries = diaryQueryRepository.findMonthlySnapshots(
                        userId,
                        yearMonth.atDay(1),
                        yearMonth.atEndOfMonth()
                );
        Map<UUID, DiaryGenerationSnapshot> generationsByDiaryId =
                findSuccessfulGenerationsByDiaryId(diaries);
        Map<LocalDate, List<DiarySummaryResult>> itemsByDate = createItemsByDate(diaries, generationsByDiaryId);
        List<DiaryDayResult> days = createDays(yearMonth, itemsByDate);
        return new DiaryTimelineResult(year, month, days);
    }

    public DiaryDetailResult getDetail(UUID userId, UUID diaryId) {
        validateUserId(userId);
        validateDiaryId(diaryId);
        DiarySnapshot diary = diaryQueryRepository.findActiveSnapshotById(diaryId)
                .orElseThrow(DiaryNotFoundException::new);
        validateOwnership(diary, userId);
        DiaryGenerationSnapshot generation = diaryGenerationQueryRepository
                .findSnapshotByDiaryId(diaryId)
                .orElseThrow(() -> new IllegalStateException(
                        "일기의 그림일기 생성 기록을 찾을 수 없습니다."
                ));
        return toDetailResult(diary, generation);
    }

    private Map<UUID, DiaryGenerationSnapshot> findSuccessfulGenerationsByDiaryId(
            List<DiarySnapshot> diaries
    ) {
        List<UUID> diaryIds = diaries.stream()
                .map(DiarySnapshot::id)
                .toList();
        if (diaryIds.isEmpty()) {
            return Map.of();
        }
        return diaryGenerationQueryRepository
                .findSnapshotsByDiaryIdInAndStatus(diaryIds, GenerationStatus.SUCCEEDED)
                .stream()
                .collect(Collectors.toMap(DiaryGenerationSnapshot::diaryId, Function.identity()));
    }

    private Map<LocalDate, List<DiarySummaryResult>> createItemsByDate(
            List<DiarySnapshot> diaries,
            Map<UUID, DiaryGenerationSnapshot> generationsByDiaryId
    ) {
        return diaries.stream()
                .filter(diary -> generationsByDiaryId.containsKey(diary.id()))
                .collect(Collectors.groupingBy(
                        DiarySnapshot::diaryDate,
                        Collectors.mapping(
                                diary -> toSummaryResult(diary, generationsByDiaryId.get(diary.id())),
                                Collectors.toList()
                        )
                ));
    }

    private List<DiaryDayResult> createDays(
            YearMonth yearMonth,
            Map<LocalDate, List<DiarySummaryResult>> itemsByDate
    ) {
        return IntStream.rangeClosed(1, yearMonth.lengthOfMonth())
                .mapToObj(yearMonth::atDay)
                .map(date -> createDay(date, itemsByDate.getOrDefault(date, List.of())))
                .toList();
    }

    private DiaryDayResult createDay(LocalDate date, List<DiarySummaryResult> items) {
        return new DiaryDayResult(date, items);
    }

    private DiarySummaryResult toSummaryResult(
            DiarySnapshot diary,
            DiaryGenerationSnapshot generation
    ) {
        return new DiarySummaryResult(
                diary.id(),
                generation.title(),
                generation.imageObjectKey()
        );
    }

    private DiaryDetailResult toDetailResult(
            DiarySnapshot diary,
            DiaryGenerationSnapshot generation
    ) {
        return new DiaryDetailResult(
                diary.id(),
                diary.diaryDate(),
                diary.sourceText(),
                diary.createdAt(),
                new DiaryGenerationResult(
                        generation.id(),
                        generation.status(),
                        generation.title(),
                        generation.imageObjectKey(),
                        generation.completedAt()
                )
        );
    }

    private void validateOwnership(DiarySnapshot diary, UUID userId) {
        if (!diary.isOwnedBy(userId)) {
            throw new DiaryAccessDeniedException();
        }
    }

    private static YearMonth createYearMonth(int year, int month) {
        try {
            return YearMonth.of(year, month);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("조회 연월이 올바르지 않습니다.", exception);
        }
    }

    private static void validateUserId(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("사용자 ID는 필수입니다.");
        }
    }

    private static void validateDiaryId(UUID diaryId) {
        if (diaryId == null) {
            throw new IllegalArgumentException("일기 ID는 필수입니다.");
        }
    }
}
