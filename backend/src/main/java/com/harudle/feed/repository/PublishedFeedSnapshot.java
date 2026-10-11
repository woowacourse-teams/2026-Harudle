package com.harudle.feed.repository;

import java.util.UUID;

public record PublishedFeedSnapshot(UUID diaryId, UUID feedId) {
}
