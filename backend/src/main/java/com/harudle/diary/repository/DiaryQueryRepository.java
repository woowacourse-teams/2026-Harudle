package com.harudle.diary.repository;

import com.harudle.diary.domain.Diary;
import com.harudle.generation.domain.GenerationStatus;
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
            ORDER BY diary.diaryDate DESC, diary.createdAt DESC, diary.id DESC
            """)
    List<DiarySnapshot> findActiveSnapshotsBetween(
            @Param("userId") UUID userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * 사용자가 한 번이라도 성공적으로 일기를 완성한 날짜를 반환한다.
     * <p>
     * 사용자 삭제가 이미 달성한 streak를 되돌리지 않도록 의도적으로 diary.deletedAt 조건을 사용하지 않는다.
     */
    @Query("""
            SELECT DISTINCT diary.diaryDate
            FROM Diary diary
            WHERE diary.userId = :userId
              AND diary.diaryDate <= :endDate
              AND EXISTS (
                  SELECT generation.id
                  FROM DiaryGeneration generation
                  WHERE generation.diaryId = diary.id
                    AND generation.status = :generationStatus
              )
            ORDER BY diary.diaryDate DESC
            """)
    List<LocalDate> findSuccessfulDiaryDatesIncludingDeleted(
            @Param("userId") UUID userId,
            @Param("endDate") LocalDate endDate,
            @Param("generationStatus") GenerationStatus generationStatus
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
