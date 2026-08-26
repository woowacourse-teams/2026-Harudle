package com.harudle.admin.infrastructure;

import com.harudle.admin.repository.AdminUserPage;
import com.harudle.admin.repository.AdminUserQueryRepository;
import com.harudle.admin.repository.AdminUserSnapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
class JpaAdminUserQueryRepository implements AdminUserQueryRepository {

    private static final String NAME_SEARCH_CONDITION = """
            (:query = '' OR LOCATE(:query, LOWER(u.name)) > 0)
            """;

    private static final String GUEST_USER_EXCLUSION = """
            NOT EXISTS (
                SELECT guestSession.id
                FROM GuestSession guestSession
                WHERE guestSession.guestUserId = u.id
            )
            """;

    private final EntityManager entityManager;

    JpaAdminUserQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public AdminUserPage search(
            String normalizedQuery,
            Optional<UUID> exactUserId,
            LocalDate usageDate,
            int page,
            int size
    ) {
        String searchCondition = createSearchCondition(exactUserId);
        TypedQuery<AdminUserSnapshot> contentQuery = entityManager.createQuery("""
                SELECT new com.harudle.admin.repository.AdminUserSnapshot(
                    u.id,
                    u.name,
                    u.createdAt,
                    u.deletedAt,
                    MAX(oa.lastLoginAt),
                    :usageDate,
                    COALESCE(usage.usedCount, 0),
                    COALESCE(usage.limitCount, u.dailyGenerationLimit)
                )
                FROM User u
                LEFT JOIN OAuthAccount oa ON oa.user = u
                LEFT JOIN DailyGenerationUsage usage
                    ON usage.id.userId = u.id AND usage.id.usageDate = :usageDate
                WHERE %s
                  AND %s
                GROUP BY u.id, u.name, u.createdAt, u.deletedAt, u.dailyGenerationLimit,
                    usage.usedCount, usage.limitCount
                ORDER BY u.createdAt DESC, u.id DESC
                """.formatted(searchCondition, GUEST_USER_EXCLUSION), AdminUserSnapshot.class);
        bindSearchParameters(contentQuery, normalizedQuery, exactUserId);
        contentQuery.setParameter("usageDate", usageDate);
        contentQuery.setFirstResult(Math.toIntExact((long) page * size));
        contentQuery.setMaxResults(size);

        TypedQuery<Long> countQuery = entityManager.createQuery("""
                SELECT COUNT(u)
                FROM User u
                WHERE %s
                  AND %s
                """.formatted(searchCondition, GUEST_USER_EXCLUSION), Long.class);
        bindSearchParameters(countQuery, normalizedQuery, exactUserId);

        return new AdminUserPage(contentQuery.getResultList(), countQuery.getSingleResult());
    }

    private String createSearchCondition(Optional<UUID> exactUserId) {
        if (exactUserId.isEmpty()) {
            return NAME_SEARCH_CONDITION;
        }
        return "(" + NAME_SEARCH_CONDITION + " OR u.id = :userId)";
    }

    private void bindSearchParameters(
            TypedQuery<?> query,
            String normalizedQuery,
            Optional<UUID> exactUserId
    ) {
        query.setParameter("query", normalizedQuery);
        exactUserId.ifPresent(userId -> query.setParameter("userId", userId));
    }
}
