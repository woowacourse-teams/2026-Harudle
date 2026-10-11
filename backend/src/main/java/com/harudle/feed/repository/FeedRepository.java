package com.harudle.feed.repository;

import com.harudle.feed.domain.Feed;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedRepository extends JpaRepository<Feed, UUID>, FeedQueryRepository {

    boolean existsByDiaryIdAndDeletedAtIsNull(UUID diaryId);
}
