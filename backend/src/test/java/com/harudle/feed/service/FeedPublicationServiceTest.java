package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.category.service.exception.CategoryInactiveException;
import com.harudle.category.service.port.CategoryReader;
import com.harudle.diary.service.port.DiaryPublicationReader;
import com.harudle.feed.repository.FeedRepository;
import com.harudle.feed.service.exception.DiaryAlreadyPublishedException;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.push.service.port.FeedPushOutbox;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class FeedPublicationServiceTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID DIARY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-11T01:00:00Z");
    private static final CategoryReader.Category CATEGORY = new CategoryReader.Category(1, "일상", 0, true);

    @Mock private DiaryPublicationReader diaries;
    @Mock private FeedRepository feeds;
    @Mock private FeedCollaborators collaborators;
    @Mock private CategoryReader categories;
    @Mock private PublicProfileReader profiles;
    @Mock private FeedPushOutbox outbox;
    private FeedPublicationService service;

    @BeforeEach
    void setUp() {
        service = new FeedPublicationService(diaries, feeds, collaborators, Clock.fixed(NOW, ZoneOffset.UTC));
        when(collaborators.pushOutbox()).thenReturn(outbox);
        when(collaborators.categories()).thenReturn(categories);
        when(collaborators.profiles()).thenReturn(profiles);
        when(diaries.lockAndRead(ACTOR, DIARY))
                .thenReturn(new DiaryPublicationReader.PublishableDiary(DIARY, ACTOR, "generated/comic.webp"));
    }

    @Test
    void publishesAndEnqueuesSameFeedInTransaction() {
        validCategoryAndProfile();
        var result = service.publish(ACTOR, DIARY, 1);
        ArgumentCaptor<FeedPushOutbox.FeedPublished> event = ArgumentCaptor.forClass(FeedPushOutbox.FeedPublished.class);
        verify(outbox).enqueuePublished(event.capture());
        assertThat(event.getValue().feedId()).isEqualTo(result.id());
        assertThat(event.getValue().publishedAt()).isEqualTo(result.publishedAt());
        assertThat(event.getValue().eventId()).isNotNull();
        assertThat(result.isMine()).isTrue();
        assertThat(result.likedByMe()).isFalse();
        assertThat(result.likeCount()).isZero();
        assertThat(result.commentCount()).isZero();
        verify(feeds).saveAndFlush(any());
    }

    @Test
    void rejectsDuplicateBeforeSavingOrEnqueuing() {
        when(categories.lockActiveForPublication(1)).thenReturn(CATEGORY);
        when(feeds.existsByDiaryIdAndDeletedAtIsNull(DIARY)).thenReturn(true);
        assertThatThrownBy(() -> service.publish(ACTOR, DIARY, 1)).isInstanceOf(DiaryAlreadyPublishedException.class);
        verifyNoInteractions(profiles, outbox);
    }

    @Test
    void rejectsInactiveCategoryWithoutWritingFeed() {
        when(categories.lockActiveForPublication(1)).thenThrow(new CategoryInactiveException());
        assertThatThrownBy(() -> service.publish(ACTOR, DIARY, 1)).isInstanceOf(CategoryInactiveException.class);
        verifyNoInteractions(feeds, profiles, outbox);
    }

    @Test
    void translatesOnlyActiveDiaryUniqueViolation() {
        validCategoryAndProfile();
        when(feeds.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "duplicate", new ConstraintViolationException("duplicate", null, "uq_feeds_active_diary")));
        assertThatThrownBy(() -> service.publish(ACTOR, DIARY, 1)).isInstanceOf(DiaryAlreadyPublishedException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void preservesUnrelatedDatabaseFailure() {
        validCategoryAndProfile();
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "foreign key", new ConstraintViolationException("foreign key", null, "fk_feeds_category"));
        when(feeds.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.publish(ACTOR, DIARY, 1)).isSameAs(failure);
        verifyNoInteractions(outbox);
    }

    private void validCategoryAndProfile() {
        when(categories.lockActiveForPublication(1)).thenReturn(CATEGORY);
        when(profiles.readAll(Set.of(ACTOR))).thenReturn(Map.of(ACTOR,
                new PublicProfileReader.Profile(ACTOR, "캐모", URI.create("https://example.com/profile.png"))));
    }
}
