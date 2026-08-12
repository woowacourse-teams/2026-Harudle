package com.harudle.diary.repository;

import com.harudle.diary.domain.Diary;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiaryRepository extends
        JpaRepository<Diary, UUID>,
        DiaryQueryRepository,
        DiaryDeletionRepository,
        DiaryLifecycleRepository {
}
