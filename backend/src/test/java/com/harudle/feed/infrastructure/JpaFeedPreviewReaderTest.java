package com.harudle.feed.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.common.testdata.DevTestDataVisibility;
import com.harudle.feed.repository.FeedQueryRepository;
import com.harudle.feed.repository.FeedSnapshot;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.profile.query.PublicProfileSnapshot;
import com.harudle.profile.repository.PublicProfileQueryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaFeedPreviewReaderTest {

    private static final UUID FEED = UUID.randomUUID();
    private static final UUID AUTHOR = UUID.randomUUID();
    private static final String IMAGE_KEY = "generated/public-comic.webp";
    private static final FeedSnapshot SNAPSHOT = new FeedSnapshot(FEED, AUTHOR, 1, IMAGE_KEY, Instant.EPOCH, 0, 0);
    private static final PublicProfileSnapshot PROFILE = new PublicProfileSnapshot(
            AUTHOR, "공개닉네임", (short) 1, "profile-key-that-never-needs-signing");

    @Mock private FeedQueryRepository feeds;
    @Mock private PublicProfileQueryRepository profiles;
    @Mock private CategoryReader categories;
    @Mock private DevTestDataVisibility visibility;
    private JpaFeedPreviewReader reader;

    @BeforeEach
    void setUp() {
        reader = new JpaFeedPreviewReader(feeds, profiles, categories, visibility);
    }

    @Test
    @DisplayName("기존 익명 공개 조건과 공개 프로필 스냅샷을 사용하고 프로필 사진 주소는 만들지 않는다")
    void readsOnlyPublicPreviewFieldsWithoutResolvingProfileImage() {
        when(feeds.findActiveSnapshotById(FEED, null)).thenReturn(Optional.of(SNAPSHOT));
        availableAuthorAndArchivedCategory();

        var details = reader.read(FEED);

        assertThat(details.authorNickname()).isEqualTo("공개닉네임");
        assertThat(details.categoryName()).isEqualTo("종료된 카테고리");
        assertThat(details.imageObjectKey()).isEqualTo(IMAGE_KEY);
        verify(profiles).findAllByUserIds(Set.of(AUTHOR));
        verify(feeds, never()).findVisibleSnapshotById(FEED, null);
    }

    @Test
    @DisplayName("dev에서는 익명 사용자용 테스트 계정 제외 조건이 있는 조회를 선택한다")
    void usesExistingDevVisibilityFilterWithAnonymousViewer() {
        when(visibility.enabled()).thenReturn(true);
        when(feeds.findVisibleSnapshotById(FEED, null)).thenReturn(Optional.of(SNAPSHOT));
        availableAuthorAndArchivedCategory();

        assertThat(reader.read(FEED).authorNickname()).isEqualTo("공개닉네임");

        verify(feeds, never()).findActiveSnapshotById(FEED, null);
    }

    @Test
    @DisplayName("dev 공개 필터가 제외한 피드는 프로필이나 카테고리를 조회하지 않는다")
    void hiddenDevFeedNeverReadsProfileOrCategory() {
        when(visibility.enabled()).thenReturn(true);

        assertThatThrownBy(() -> reader.read(FEED)).isInstanceOf(FeedNotFoundException.class);

        verify(feeds).findVisibleSnapshotById(FEED, null);
        verifyNoInteractions(profiles, categories);
    }

    @Test
    @DisplayName("없는 피드는 프로필과 카테고리 조회 전에 거절한다")
    void missingFeedNeverReadsProfileOrCategory() {
        assertThatThrownBy(() -> reader.read(FEED)).isInstanceOf(FeedNotFoundException.class);

        verifyNoInteractions(profiles, categories);
    }

    @Test
    @DisplayName("탈퇴하거나 공개 프로필이 미할당된 작성자는 기존과 같이 없는 피드로 처리한다")
    void authorMissingFromPublicProfileSnapshotIsNotFound() {
        when(feeds.findActiveSnapshotById(FEED, null)).thenReturn(Optional.of(SNAPSHOT));

        assertThatThrownBy(() -> reader.read(FEED)).isInstanceOf(FeedNotFoundException.class);

        verify(profiles).findAllByUserIds(Set.of(AUTHOR));
        verifyNoInteractions(categories);
    }

    @Test
    @DisplayName("다른 회원의 프로필 스냅샷을 작성자 정보로 사용하지 않는다")
    void unrelatedProfileCannotBecomeFeedAuthor() {
        when(feeds.findActiveSnapshotById(FEED, null)).thenReturn(Optional.of(SNAPSHOT));
        when(profiles.findAllByUserIds(Set.of(AUTHOR))).thenReturn(List.of(
                new PublicProfileSnapshot(UUID.randomUUID(), "다른회원", (short) 1, null)));

        assertThatThrownBy(() -> reader.read(FEED)).isInstanceOf(FeedNotFoundException.class);

        verifyNoInteractions(categories);
    }

    private void availableAuthorAndArchivedCategory() {
        when(profiles.findAllByUserIds(Set.of(AUTHOR))).thenReturn(List.of(PROFILE));
        when(categories.get(1)).thenReturn(new CategoryReader.Category(1, "종료된 카테고리", 0, false));
    }
}
