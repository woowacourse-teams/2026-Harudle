package com.harudle.admin.repository;

import com.harudle.generation.domain.GenerationUsage;
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

    private static final String SEARCH_CONDITION = """
            (:query = ''
                OR LOCATE(:query, LOWER(u.name)) > 0
                OR LOCATE(:query, LOWER(COALESCE(u.primaryEmail, ''))) > 0)
            """;

    private final EntityManager entityManager;

    JpaAdminUserQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public AdminUserDetail findDetail(UUID userId, LocalDate usageDate) {
        List<AdminUserDetail> users = entityManager.createQuery("""
                SELECT new com.harudle.admin.repository.AdminUserDetail(
                    u.id, u.name, u.primaryEmail, u.createdAt, u.deletedAt, MAX(oa.lastLoginAt),
                    :usageDate, COALESCE(usage.usedCount, 0), COALESCE(usage.limitCount, :defaultLimit),
                    null)
                FROM User u LEFT JOIN OAuthAccount oa ON oa.user = u
                LEFT JOIN DailyGenerationUsage usage
                    ON usage.id.userId = u.id AND usage.id.usageDate = :usageDate
                WHERE u.id = :userId
                GROUP BY u.id, u.name, u.primaryEmail, u.createdAt, u.deletedAt, usage.usedCount, usage.limitCount
                """, AdminUserDetail.class)
                .setParameter("userId", userId).setParameter("usageDate", usageDate)
                .setParameter("defaultLimit", GenerationUsage.DEFAULT_LIMIT_COUNT).getResultList();
        if (users.isEmpty()) throw new AdminUserNotFoundException(userId);
        AdminUserDetail user = users.getFirst();
        List<AdminUserDetail.RecentGeneration> generations = entityManager.createQuery("""
                SELECT new com.harudle.admin.repository.AdminUserDetail$RecentGeneration(
                    generation.id, generation.createdAt, generation.status, generation.completedAt,
                    generation.errorCode)
                FROM DiaryGeneration generation JOIN Diary diary ON diary.id = generation.diaryId
                WHERE diary.userId = :userId ORDER BY generation.createdAt DESC, generation.id DESC
                """, AdminUserDetail.RecentGeneration.class)
                .setParameter("userId", userId).setMaxResults(5).getResultList();
        return new AdminUserDetail(user.id(), user.name(), user.email(), user.createdAt(), user.deletedAt(),
                user.lastLoginAt(), user.usageDate(), user.usedCount(), user.limitCount(), generations);
    }

    @Override
    public AdminUserPage search(
            String normalizedQuery,
            Optional<UUID> exactUserId,
            LocalDate usageDate,
            int page,
            int size
    ) {
        String condition = exactUserId.isPresent()
                ? "(" + SEARCH_CONDITION + " OR u.id = :userId)"
                : SEARCH_CONDITION;

        TypedQuery<AdminUserSnapshot> contentQuery = entityManager.createQuery("""
                SELECT new com.harudle.admin.repository.AdminUserSnapshot(
                    u.id,
                    u.name,
                    u.primaryEmail,
                    u.createdAt,
                    u.deletedAt,
                    MAX(oa.lastLoginAt),
                    COALESCE(usage.limitCount - usage.usedCount, :defaultLimit)
                )
                FROM User u
                LEFT JOIN OAuthAccount oa ON oa.user = u
                LEFT JOIN DailyGenerationUsage usage
                    ON usage.id.userId = u.id AND usage.id.usageDate = :usageDate
                WHERE """ + condition + """
                GROUP BY u.id, u.name, u.primaryEmail, u.createdAt, u.deletedAt,
                    usage.limitCount, usage.usedCount
                ORDER BY u.createdAt DESC, u.id DESC
                """, AdminUserSnapshot.class);
        bindSearchParameters(contentQuery, normalizedQuery, exactUserId);
        contentQuery.setParameter("usageDate", usageDate);
        contentQuery.setParameter("defaultLimit", GenerationUsage.DEFAULT_LIMIT_COUNT);
        contentQuery.setFirstResult(page * size);
        contentQuery.setMaxResults(size);

        TypedQuery<Long> countQuery = entityManager.createQuery("""
                SELECT COUNT(u)
                FROM User u
                WHERE """ + condition, Long.class);
        bindSearchParameters(countQuery, normalizedQuery, exactUserId);

        List<AdminUserSnapshot> content = contentQuery.getResultList();
        return new AdminUserPage(content, countQuery.getSingleResult());
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
