package com.harudle.diary.repository;

import com.harudle.diary.domain.Diary;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@NoRepositoryBean
public interface DiaryQueryRepository extends Repository<Diary, UUID> {

    @Query("""
            SELECT new com.harudle.diary.repository.DiarySnapshot(
                diary.id,
                diary.userId,
                diary.diaryDate,
                diary.sourceText,
                diary.createdAt
            )
            FROM Diary diary
            WHERE diary.userId = :userId
              AND diary.diaryDate BETWEEN :startDate AND :endDate
              AND diary.deletedAt IS NULL
            ORDER BY diary.diaryDate ASC, diary.createdAt ASC, diary.id ASC
            """)
    List<DiarySnapshot> findMonthlySnapshots(
            @Param("userId") UUID userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query("""
            SELECT new com.harudle.diary.repository.DiarySnapshot(
                diary.id,
                diary.userId,
                diary.diaryDate,
                diary.sourceText,
                diary.createdAt
            )
            FROM Diary diary
            WHERE diary.id = :diaryId
              AND diary.deletedAt IS NULL
            """)
    Optional<DiarySnapshot> findActiveSnapshotById(@Param("diaryId") UUID diaryId);
}
