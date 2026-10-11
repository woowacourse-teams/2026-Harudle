package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.category.service.exception.CategoryNotFoundException;
import com.harudle.category.service.port.CategoryReader;
import com.harudle.feed.query.FeedCursor;
import com.harudle.feed.query.FeedSort;
import com.harudle.feed.repository.FeedListRepository;
import com.harudle.feed.repository.FeedSnapshot;
import com.harudle.feed.service.exception.InvalidFeedCursorException;
import com.harudle.feed.service.port.FeedLikeReader;
import com.harudle.profile.service.port.PublicProfileReader;
import java.net.URI;
import java.time.Instant;
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
class FeedListServiceTest {
    private static final UUID AUTHOR = UUID.randomUUID();
    @Mock private FeedListRepository feeds;
    @Mock private FeedCollaborators collaborators;
    @Mock private CategoryReader categories;
    @Mock private PublicProfileReader profiles;
    @Mock private FeedLikeReader likes;
    private final FeedCursorCodec cursors = new FeedCursorCodec();
    private FeedListService service;

    @BeforeEach
    void setUp() {
        service = new FeedListService(feeds, collaborators, cursors);
    }

    @Test
    void returnsSizeItemsAndCursorOfLastReturnedItemUsingOneBatchOfMetadata() {
        var first = snapshot(AUTHOR, 10);
        var second = snapshot(AUTHOR, 5);
        var extra = snapshot(AUTHOR, 1);
        when(feeds.findAfter(FeedSort.POPULAR, null, null, 3)).thenReturn(List.of(first, second, extra));
        metadata();
        var result = service.getList(null, FeedSort.POPULAR, null, null, 2);
        assertThat(result.items()).extracting(item -> item.id()).containsExactly(first.id(), second.id());
        assertThat(result.hasNext()).isTrue();
        assertThat(cursors.decode(result.nextCursor(), FeedSort.POPULAR, null))
                .isEqualTo(FeedCursor.after(FeedSort.POPULAR, null, second));
        verify(profiles).readAll(Set.of(AUTHOR));
        verify(categories).get(1);
        verifyNoInteractions(likes);
    }

    @Test
    void personalizesLikesOnceForReturnedFeedIds() {
        var first = snapshot(AUTHOR, 10);
        var second = snapshot(AUTHOR, 5);
        when(feeds.findAfter(FeedSort.LATEST, null, null, 3)).thenReturn(List.of(first, second));
        metadata();
        when(collaborators.likes()).thenReturn(likes);
        when(likes.findLikedFeedIds(AUTHOR, Set.of(first.id(), second.id()))).thenReturn(Set.of(second.id()));
        var result = service.getList(AUTHOR, FeedSort.LATEST, null, null, 2);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.items()).allMatch(item -> item.isMine());
        assertThat(result.items().getFirst().likedByMe()).isFalse();
        assertThat(result.items().getLast().likedByMe()).isTrue();
        verify(likes).findLikedFeedIds(AUTHOR, Set.of(first.id(), second.id()));
    }

    @Test
    void returnsEmptyLastPageWithoutRequiringUnusedAdapters() {
        when(feeds.findAfter(FeedSort.LATEST, null, null, 21)).thenReturn(List.of());
        var result = service.getList(AUTHOR, FeedSort.LATEST, null, null, 20);
        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.hasNext()).isFalse();
        verifyNoInteractions(collaborators);
    }

    @Test
    void validatesCategoryEvenWhenThereAreNoFeedsAndAllowsArchivedCategory() {
        when(collaborators.categories()).thenReturn(categories);
        when(categories.get(1)).thenReturn(new CategoryReader.Category(1, "일상", 0, false));
        when(feeds.findAfter(FeedSort.LATEST, 1L, null, 21)).thenReturn(List.of());
        assertThat(service.getList(null, FeedSort.LATEST, 1L, null, 20).items()).isEmpty();
        verify(categories).get(1);
        when(categories.get(99)).thenThrow(new CategoryNotFoundException());
        assertThatThrownBy(() -> service.getList(null, FeedSort.LATEST, 99L, null, 20))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void rejectsCursorConditionMismatchBeforeDatabaseLookup() {
        String cursor = cursors.encode(FeedCursor.after(FeedSort.POPULAR, 1L, snapshot(AUTHOR, 10)));
        assertThatThrownBy(() -> service.getList(null, FeedSort.LATEST, 1L, cursor, 20))
                .isInstanceOf(InvalidFeedCursorException.class);
        verifyNoInteractions(feeds, collaborators);
    }

    @Test
    void refillsPageWhenProfileDisappearsAndUsesScannedBoundaryWithoutRereadingAuthors() {
        UUID unavailable = UUID.randomUUID();
        var hidden = snapshot(unavailable, 20);
        var first = snapshot(AUTHOR, 10);
        var second = snapshot(AUTHOR, 5);
        when(feeds.findAfter(FeedSort.POPULAR, null, null, 2)).thenReturn(List.of(hidden, first));
        when(feeds.findAfter(FeedSort.POPULAR, null, FeedCursor.after(FeedSort.POPULAR, null, first), 2))
                .thenReturn(List.of(second));
        metadata();
        var result = service.getList(null, FeedSort.POPULAR, null, null, 1);
        assertThat(result.items()).extracting(item -> item.id()).containsExactly(first.id());
        assertThat(result.hasNext()).isTrue();
        verify(profiles).readAll(Set.of(AUTHOR, unavailable));
        assertThat(cursors.decode(result.nextCursor(), FeedSort.POPULAR, null))
                .isEqualTo(FeedCursor.after(FeedSort.POPULAR, null, first));
    }

    @Test
    void forwardsDecodedCursorAndAllowsChangingPageSize() {
        FeedCursor boundary = FeedCursor.after(FeedSort.LATEST, null, snapshot(AUTHOR, 0));
        when(feeds.findAfter(FeedSort.LATEST, null, boundary, 51)).thenReturn(List.of());
        service.getList(null, FeedSort.LATEST, null, cursors.encode(boundary), 50);
        verify(feeds).findAfter(FeedSort.LATEST, null, boundary, 51);
    }

    private void metadata() {
        when(collaborators.profiles()).thenReturn(profiles);
        when(profiles.readAll(anySet())).thenReturn(Map.of(AUTHOR,
                new PublicProfileReader.Profile(AUTHOR, "캐모", URI.create("https://example.com/profile.png"))));
        when(collaborators.categories()).thenReturn(categories);
        when(categories.get(1)).thenReturn(new CategoryReader.Category(1, "일상", 0, true));
    }

    private static FeedSnapshot snapshot(UUID author, int likes) {
        return new FeedSnapshot(UUID.randomUUID(), author, 1, "generated/comic.webp",
                Instant.parse("2026-10-11T01:00:00.123456Z"), likes, 3);
    }
}
