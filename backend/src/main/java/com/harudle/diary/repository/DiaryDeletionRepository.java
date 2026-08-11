package com.harudle.diary.repository;

import com.harudle.diary.domain.Diary;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@NoRepositoryBean
public interface DiaryDeletionRepository extends Repository<Diary, UUID> {

    @Query("""
            SELECT diary
            FROM Diary diary
            WHERE diary.id = :diaryId
              AND diary.deletedAt IS NULL
            """)
    Optional<Diary> findActiveById(@Param("diaryId") UUID diaryId);
}
