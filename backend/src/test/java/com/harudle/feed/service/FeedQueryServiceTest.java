package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.feed.repository.FeedQueryRepository;
import com.harudle.feed.repository.FeedSnapshot;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedLikeReader;
import com.harudle.profile.service.port.PublicProfileReader;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeedQueryServiceTest {

    private static final UUID FEED = UUID.randomUUID();
    private static final UUID AUTHOR = UUID.randomUUID();
    @Mock private FeedQueryRepository feeds;
    @Mock private FeedCollaborators collaborators;
    @Mock private PublicProfileReader profiles;
    @Mock private CategoryReader categories;
    @Mock private FeedLikeReader likes;
    private FeedQueryService service;

    @BeforeEach
    void setUp() {
        service = new FeedQueryService(feeds, collaborators);
    }

    @Test
    void anonymousReadsArchivedCategoryWithoutRequestingLikes() {
        activeFeedAndProfiles();
        var result = service.getDetail(null, FEED);
        assertThat(result.likedByMe()).isFalse();
        assertThat(result.isMine()).isFalse();
        assertThat(result.category().active()).isFalse();
        verifyNoInteractions(likes);
    }

    @Test
    void personalizesOwnerAndLikeState() {
        activeFeedAndProfiles();
        when(collaborators.likes()).thenReturn(likes);
        when(likes.findLikedFeedIds(AUTHOR, Set.of(FEED))).thenReturn(Set.of(FEED));
        var result = service.getDetail(AUTHOR, FEED);
        assertThat(result.isMine()).isTrue();
        assertThat(result.likedByMe()).isTrue();
    }

    @Test
    void rejectsMissingOrDeletedFeedWithoutReadingCollaborators() {
        when(feeds.findActiveSnapshotById(FEED)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDetail(null, FEED)).isInstanceOf(FeedNotFoundException.class);
        verifyNoInteractions(collaborators);
    }

    @Test
    void hidesUnavailableAuthorsProfile() {
        when(feeds.findActiveSnapshotById(FEED)).thenReturn(Optional.of(snapshot()));
        when(collaborators.profiles()).thenReturn(profiles);
        when(profiles.readAll(Set.of(AUTHOR))).thenReturn(Map.of());
        assertThatThrownBy(() -> service.getDetail(null, FEED)).isInstanceOf(FeedNotFoundException.class);
        verifyNoInteractions(categories, likes);
    }

    private void activeFeedAndProfiles() {
        when(feeds.findActiveSnapshotById(FEED)).thenReturn(Optional.of(snapshot()));
        when(collaborators.profiles()).thenReturn(profiles);
        when(collaborators.categories()).thenReturn(categories);
        when(profiles.readAll(Set.of(AUTHOR))).thenReturn(Map.of(AUTHOR,
                new PublicProfileReader.Profile(AUTHOR, "현재닉네임", URI.create("https://example.com/profile.png"))));
        when(categories.get(1)).thenReturn(new CategoryReader.Category(1, "변경된이름", 0, false));
    }

    private FeedSnapshot snapshot() {
        return new FeedSnapshot(FEED, AUTHOR, 1, "generated/comic.webp", Instant.now(), 12, 3);
    }
}
