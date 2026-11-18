package com.harudle.feed.infrastructure;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.common.testdata.DevTestDataVisibility;
import com.harudle.feed.repository.FeedQueryRepository;
import com.harudle.feed.repository.FeedSnapshot;
import com.harudle.feed.service.dto.FeedPreviewDetails;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedPreviewReader;
import com.harudle.profile.query.PublicProfileSnapshot;
import com.harudle.profile.repository.PublicProfileQueryRepository;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 기존 익명 공개 조건을 재사용하며, 사용하지 않는 프로필 사진 주소는 서명하지 않는다. */
@Repository
public class JpaFeedPreviewReader implements FeedPreviewReader {

    private final FeedQueryRepository feeds;
    private final PublicProfileQueryRepository profiles;
    private final CategoryReader categories;
    private final DevTestDataVisibility visibility;

    public JpaFeedPreviewReader(FeedQueryRepository feeds, PublicProfileQueryRepository profiles,
            CategoryReader categories, DevTestDataVisibility visibility) {
        this.feeds = feeds;
        this.profiles = profiles;
        this.categories = categories;
        this.visibility = visibility;
    }

    @Override
    @Transactional(readOnly = true)
    public FeedPreviewDetails read(UUID feedId) {
        Objects.requireNonNull(feedId, "피드 ID는 필수입니다.");
        FeedSnapshot feed = (visibility.enabled() ? feeds.findVisibleSnapshotById(feedId, null)
                : feeds.findActiveSnapshotById(feedId, null)).orElseThrow(FeedNotFoundException::new);
        PublicProfileSnapshot author = profiles.findAllByUserIds(Set.of(feed.authorId())).stream()
                .filter(profile -> feed.authorId().equals(profile.id()))
                .findFirst().orElseThrow(FeedNotFoundException::new);
        return new FeedPreviewDetails(author.nickname(), categories.get(feed.categoryId()).name(), feed.imageObjectKey());
    }
}
