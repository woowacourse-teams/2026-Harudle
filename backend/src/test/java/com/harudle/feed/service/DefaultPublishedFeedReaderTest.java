package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.feed.repository.PublishedFeedRepository;
import com.harudle.feed.repository.PublishedFeedSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultPublishedFeedReaderTest {
    @Mock private PublishedFeedRepository feeds;
    private DefaultPublishedFeedReader reader;

    @BeforeEach
    void setUp() {
        reader = new DefaultPublishedFeedReader(feeds);
    }

    @Test
    void returnsEmptyForEmptyInputWithoutDatabaseLookup() {
        assertThat(reader.findByDiaryIds(Set.of())).isEmpty();
        verifyNoInteractions(feeds);
    }

    @Test
    void readsMultipleDiariesOnceAndOmitsUnpublishedDiaries() {
        UUID firstDiary = UUID.randomUUID();
        UUID secondDiary = UUID.randomUUID();
        UUID unpublishedDiary = UUID.randomUUID();
        UUID firstFeed = UUID.randomUUID();
        UUID secondFeed = UUID.randomUUID();
        Set<UUID> ids = Set.of(firstDiary, secondDiary, unpublishedDiary);
        when(feeds.findActiveSnapshotsByDiaryIds(ids)).thenReturn(List.of(
                new PublishedFeedSnapshot(secondDiary, secondFeed),
                new PublishedFeedSnapshot(firstDiary, firstFeed)
        ));
        assertThat(reader.findByDiaryIds(ids)).isEqualTo(Map.of(firstDiary, firstFeed, secondDiary, secondFeed));
        verify(feeds).findActiveSnapshotsByDiaryIds(ids);
    }

    @Test
    void returnsEmptyWhenNoActivePublicationExists() {
        Set<UUID> ids = Set.of(UUID.randomUUID());
        when(feeds.findActiveSnapshotsByDiaryIds(ids)).thenReturn(List.of());
        assertThat(reader.findByDiaryIds(ids)).isEmpty();
    }

    @Test
    void rejectsNullInputBeforeDatabaseLookup() {
        assertThatThrownBy(() -> reader.findByDiaryIds(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(feeds);
    }
}
