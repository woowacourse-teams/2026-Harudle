package com.harudle.feed.service;

import com.harudle.feed.repository.PublishedFeedRepository;
import com.harudle.feed.repository.PublishedFeedSnapshot;
import com.harudle.feed.service.port.PublishedFeedReader;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultPublishedFeedReader implements PublishedFeedReader {
    private final PublishedFeedRepository feeds;

    public DefaultPublishedFeedReader(PublishedFeedRepository feeds) {
        this.feeds = feeds;
    }

    @Override
    public Map<UUID, UUID> findByDiaryIds(Set<UUID> diaryIds) {
        Set<UUID> ids = Set.copyOf(diaryIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return feeds.findActiveSnapshotsByDiaryIds(ids).stream()
                .collect(Collectors.toUnmodifiableMap(PublishedFeedSnapshot::diaryId, PublishedFeedSnapshot::feedId));
    }
}
