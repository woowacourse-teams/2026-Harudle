package com.harudle.push.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PostgresFeedPushPublicationGuardTest {
    @Mock private EntityManager entityManager;
    @Mock private Query query;
    private final List<Lock> locks = new ArrayList<>();
    private final Map<String, Object> parameters = new HashMap<>();
    private String statement;
    private PostgresFeedPushPublicationGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PostgresFeedPushPublicationGuard(entityManager);
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            statement = invocation.getArgument(0);
            return query;
        });
        when(query.setParameter(anyString(), any())).thenAnswer(invocation -> {
            parameters.put(invocation.getArgument(0), invocation.getArgument(1));
            return query;
        });
        when(query.getSingleResult()).thenAnswer(invocation -> {
            locks.add(new Lock(statement, (Integer) parameters.get("high"), (Integer) parameters.get("low")));
            return 1;
        });
    }

    @Test
    void locksActualIntegerKeysInCommonOrderForEitherAuthorDirection() {
        List<UUID> authors = IntStream.rangeClosed(1, 8).mapToObj(value -> new UUID(0, value)).toList();
        Comparator<Lock> order = Comparator.comparingInt(Lock::high).thenComparingInt(Lock::low);
        for (int first = 0; first < authors.size(); first++) {
            for (int second = first + 1; second < authors.size(); second++) {
                locks.clear();
                guard.lockForBlock(authors.get(first), authors.get(second));
                List<Lock> forward = List.copyOf(locks);
                assertThat(forward).hasSize(2).isSortedAccordingTo(order);
                assertThat(forward).allSatisfy(lock -> assertThat(lock.sql())
                        .isEqualTo("SELECT 1 FROM pg_advisory_xact_lock(:high, :low)"));
                locks.clear();
                guard.lockForBlock(authors.get(second), authors.get(first));
                assertThat(locks).containsExactlyElementsOf(forward);
            }
        }
    }

    @Test
    void usesSameTwoIntegerKeyForSharedPublicationAndExclusiveBlock() {
        UUID author = new UUID(0, 1);
        guard.lockForPublication(author);
        Lock publication = locks.getFirst();
        assertThat(publication.sql()).isEqualTo("SELECT 1 FROM pg_advisory_xact_lock_shared(:high, :low)");
        locks.clear();

        guard.lockForBlock(author, author);

        assertThat(locks).containsExactly(new Lock("SELECT 1 FROM pg_advisory_xact_lock(:high, :low)",
                publication.high(), publication.low()));
    }

    private record Lock(String sql, int high, int low) { }
}
