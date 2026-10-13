package com.harudle.feed.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaFeedLikeReaderTest {

    @Mock
    private EntityManager entityManager;
    @Mock
    private Query query;
    private JpaFeedLikeReader reader;

    @BeforeEach
    void setUp() {
        reader = new JpaFeedLikeReader(entityManager);
    }

    @Test
    void emptyInputDoesNotQueryDatabase() {
        assertThat(reader.findLikedFeedIds(UUID.randomUUID(), Set.of())).isEmpty();
        verifyNoInteractions(entityManager);
    }

    @Test
    void bindsViewerAndRequestedFeedIdsAndReturnsLikedIds() {
        UUID viewerId = UUID.randomUUID();
        UUID likedId = UUID.randomUUID();
        Set<UUID> requestedIds = Set.of(likedId, UUID.randomUUID());
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(query);
        when(query.setParameter("viewerId", viewerId)).thenReturn(query);
        when(query.setParameter("feedIds", requestedIds)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(likedId));

        assertThat(reader.findLikedFeedIds(viewerId, requestedIds)).containsExactly(likedId);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("f.deleted_at IS NULL", "d.deleted_at IS NULL");
    }

    @Test
    void rejectsNullViewerAndNullInputBeforeDatabaseAccess() {
        assertThatNullPointerException().isThrownBy(() -> reader.findLikedFeedIds(null, Set.of()));
        assertThatNullPointerException().isThrownBy(() -> reader.findLikedFeedIds(UUID.randomUUID(), null));
        verifyNoInteractions(entityManager);
    }

    @Test
    void rejectsNullFeedIdBeforeDatabaseAccess() {
        Set<UUID> ids = new HashSet<>();
        ids.add(null);
        assertThatNullPointerException().isThrownBy(() -> reader.findLikedFeedIds(UUID.randomUUID(), ids));
        verifyNoInteractions(entityManager);
    }
}
