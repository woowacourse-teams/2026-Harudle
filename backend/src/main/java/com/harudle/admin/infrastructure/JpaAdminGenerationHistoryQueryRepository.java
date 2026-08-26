package com.harudle.admin.infrastructure;

import com.harudle.admin.query.AdminGenerationHistoryPage;
import com.harudle.admin.query.AdminGenerationHistorySnapshot;
import com.harudle.admin.repository.AdminGenerationHistoryQueryRepository;
import com.harudle.generation.domain.GenerationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
class JpaAdminGenerationHistoryQueryRepository implements AdminGenerationHistoryQueryRepository {

    private static final String FROM_CLAUSE = """
            FROM DiaryGeneration generation
            JOIN Diary diary ON diary.id = generation.diaryId
            JOIN User user ON user.id = diary.userId
            """;

    private final EntityManager entityManager;

    JpaAdminGenerationHistoryQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public AdminGenerationHistoryPage search(
            Optional<UUID> userId,
            Optional<GenerationStatus> status,
            Optional<Instant> fromInclusive,
            Optional<Instant> toExclusive,
            int page,
            int size
    ) {
        String whereClause = createWhereClause(userId, status, fromInclusive, toExclusive);
        TypedQuery<AdminGenerationHistorySnapshot> contentQuery = entityManager.createQuery(
                """
                        SELECT new com.harudle.admin.query.AdminGenerationHistorySnapshot(
                            generation.id,
                            user.id,
                            user.name,
                            generation.createdAt,
                            generation.status,
                            generation.completedAt,
                            generation.errorCode
                        )
                        """ + FROM_CLAUSE + whereClause
                        + " ORDER BY generation.createdAt DESC, generation.id DESC",
                AdminGenerationHistorySnapshot.class
        );
        bindParameters(contentQuery, userId, status, fromInclusive, toExclusive);
        contentQuery.setFirstResult(Math.toIntExact((long) page * size));
        contentQuery.setMaxResults(size);

        TypedQuery<Long> countQuery = entityManager.createQuery(
                "SELECT COUNT(generation.id) " + FROM_CLAUSE + whereClause,
                Long.class
        );
        bindParameters(countQuery, userId, status, fromInclusive, toExclusive);

        return new AdminGenerationHistoryPage(contentQuery.getResultList(), countQuery.getSingleResult());
    }

    private String createWhereClause(
            Optional<UUID> userId,
            Optional<GenerationStatus> status,
            Optional<Instant> fromInclusive,
            Optional<Instant> toExclusive
    ) {
        List<String> conditions = new ArrayList<>();
        userId.ifPresent(value -> conditions.add("diary.userId = :userId"));
        status.ifPresent(value -> conditions.add("generation.status = :status"));
        fromInclusive.ifPresent(value -> conditions.add("generation.createdAt >= :fromInclusive"));
        toExclusive.ifPresent(value -> conditions.add("generation.createdAt < :toExclusive"));
        return " WHERE " + String.join(" AND ", conditions.isEmpty() ? List.of("1 = 1") : conditions);
    }

    private void bindParameters(
            TypedQuery<?> query,
            Optional<UUID> userId,
            Optional<GenerationStatus> status,
            Optional<Instant> fromInclusive,
            Optional<Instant> toExclusive
    ) {
        userId.ifPresent(value -> query.setParameter("userId", value));
        status.ifPresent(value -> query.setParameter("status", value));
        fromInclusive.ifPresent(value -> query.setParameter("fromInclusive", value));
        toExclusive.ifPresent(value -> query.setParameter("toExclusive", value));
    }
}
