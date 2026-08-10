package com.harudle.diary.repository;

import com.harudle.diary.domain.Diary;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryRepository extends JpaRepository<Diary, UUID> {

    List<Diary> findAllByUserIdAndDiaryDateBetweenAndDeletedAtIsNullOrderByDiaryDateAscCreatedAtAsc(
            UUID userId,
            LocalDate startDate,
            LocalDate endDate
    );
}
