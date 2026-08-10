package com.harudle.diary.service;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.DiaryDayResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.diary.service.dto.DiarySummaryResult;
import com.harudle.diary.service.dto.DiaryTimelineResult;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.domain.ComicGeneration;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.repository.ComicGenerationRepository;
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

    private final DiaryRepository diaryRepository;
    private final ComicGenerationRepository comicGenerationRepository;

    public DiaryQueryService(
            DiaryRepository diaryRepository,
            ComicGenerationRepository comicGenerationRepository
    ) {
        this.diaryRepository = diaryRepository;
        this.comicGenerationRepository = comicGenerationRepository;
    }

    public DiaryTimelineResult getTimeline(UUID userId, int year, int month) {
        validateUserId(userId);
        YearMonth yearMonth = createYearMonth(year, month);
        List<Diary> diaries = diaryRepository
                .findAllByUserIdAndDiaryDateBetweenAndDeletedAtIsNullOrderByDiaryDateAscCreatedAtAsc(
                        userId,
                        yearMonth.atDay(1),
                        yearMonth.atEndOfMonth()
                );
        Map<UUID, ComicGeneration> generationsByDiaryId = findSuccessfulGenerationsByDiaryId(diaries);
        Map<LocalDate, List<DiarySummaryResult>> itemsByDate = createItemsByDate(diaries, generationsByDiaryId);
        List<DiaryDayResult> days = createDays(yearMonth, itemsByDate);
        return new DiaryTimelineResult(year, month, days);
    }

    public DiaryDetailResult getDetail(UUID userId, UUID diaryId) {
        validateUserId(userId);
        validateDiaryId(diaryId);
        Diary diary = diaryRepository.findById(diaryId)
                .orElseThrow(DiaryNotFoundException::new);
        validateAccessibleDiary(diary, userId);
        ComicGeneration generation = comicGenerationRepository.findByDiaryId(diaryId)
                .orElseThrow(() -> new IllegalStateException(
                        "일기의 만화 생성 기록을 찾을 수 없습니다."
                ));
        return toDetailResult(diary, generation);
    }

    private Map<UUID, ComicGeneration> findSuccessfulGenerationsByDiaryId(List<Diary> diaries) {
        List<UUID> diaryIds = diaries.stream()
                .map(Diary::getId)
                .toList();
        if (diaryIds.isEmpty()) {
            return Map.of();
        }
        return comicGenerationRepository.findAllByDiaryIdIn(diaryIds).stream()
                .filter(generation -> generation.getStatus() == GenerationStatus.SUCCEEDED)
                .collect(Collectors.toMap(ComicGeneration::getDiaryId, Function.identity()));
    }

    private Map<LocalDate, List<DiarySummaryResult>> createItemsByDate(
            List<Diary> diaries,
            Map<UUID, ComicGeneration> generationsByDiaryId
    ) {
        return diaries.stream()
                .filter(diary -> generationsByDiaryId.containsKey(diary.getId()))
                .collect(Collectors.groupingBy(
                        Diary::getDiaryDate,
                        Collectors.mapping(
                                diary -> toSummaryResult(diary, generationsByDiaryId.get(diary.getId())),
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
        return new DiaryDayResult(date, !items.isEmpty(), items);
    }

    private DiarySummaryResult toSummaryResult(Diary diary, ComicGeneration generation) {
        return new DiarySummaryResult(
                diary.getId(),
                generation.getTitle(),
                generation.getImageObjectKey()
        );
    }

    private DiaryDetailResult toDetailResult(Diary diary, ComicGeneration generation) {
        return new DiaryDetailResult(
                diary.getId(),
                diary.getDiaryDate(),
                diary.getSourceText(),
                diary.getCreatedAt(),
                new DiaryGenerationResult(
                        generation.getId(),
                        generation.getStatus(),
                        generation.getTitle(),
                        generation.getImageObjectKey(),
                        generation.getCompletedAt()
                )
        );
    }

    private void validateAccessibleDiary(Diary diary, UUID userId) {
        if (diary.isDeleted()) {
            throw new DiaryNotFoundException();
        }
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
