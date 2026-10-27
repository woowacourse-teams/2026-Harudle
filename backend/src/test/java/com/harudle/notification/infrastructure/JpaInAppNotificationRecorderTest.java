package com.harudle.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.notification.service.port.InAppNotificationRecorder.Interaction;
import com.harudle.notification.service.port.InAppNotificationRecorder.Result;
import com.harudle.notification.service.port.InAppNotificationRecorder.Type;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaInAppNotificationRecorderTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    @Mock
    private EntityManager entityManager;
    @Mock
    private Query query;
    private JpaInAppNotificationRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new JpaInAppNotificationRecorder(entityManager);
    }

    @Test
    void skipsSelfNotificationWithoutDatabaseAccess() {
        UUID self = UUID.randomUUID();
        Interaction event = new Interaction(UUID.randomUUID(), Type.LIKE, UUID.randomUUID(), self, self, NOW);
        assertThat(recorder.record(event)).isEqualTo(Result.SELF_SKIPPED);
        verifyNoInteractions(entityManager);
    }

    @Test
    void returnsCreatedOrDuplicateAccordingToAffectedRows() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(1, 0);
        Interaction event = new Interaction(
                UUID.randomUUID(), Type.COMMENT, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), NOW
        );

        assertThat(recorder.record(event)).isEqualTo(Result.CREATED);
        assertThat(recorder.record(event)).isEqualTo(Result.DUPLICATE);
    }

    @Test
    void validatesEveryFieldEvenForSelfNotification() {
        UUID self = UUID.randomUUID();
        UUID action = UUID.randomUUID();
        UUID feed = UUID.randomUUID();
        assertThatNullPointerException().isThrownBy(() -> recorder.record(null));
        assertThatNullPointerException().isThrownBy(() ->
                recorder.record(new Interaction(null, Type.LIKE, feed, self, self, NOW)));
        assertThatNullPointerException().isThrownBy(() ->
                recorder.record(new Interaction(action, null, feed, self, self, NOW)));
        assertThatNullPointerException().isThrownBy(() ->
                recorder.record(new Interaction(action, Type.LIKE, null, self, self, NOW)));
        assertThatNullPointerException().isThrownBy(() ->
                recorder.record(new Interaction(action, Type.LIKE, feed, null, self, NOW)));
        assertThatNullPointerException().isThrownBy(() ->
                recorder.record(new Interaction(action, Type.LIKE, feed, self, null, NOW)));
        assertThatNullPointerException().isThrownBy(() ->
                recorder.record(new Interaction(action, Type.LIKE, feed, self, self, null)));
        verifyNoInteractions(entityManager);
    }
}
