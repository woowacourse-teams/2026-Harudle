package com.harudle.admin.repository;

import com.harudle.generation.domain.GenerationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
class JpaAdminGenerationQueryRepository implements AdminGenerationQueryRepository {
    private final EntityManager entityManager;

    JpaAdminGenerationQueryRepository(EntityManager entityManager) { this.entityManager = entityManager; }

    @Override
    public AdminGenerationPage search(UUID userId, GenerationStatus status, Instant from, Instant to, int page, int size) {
        String condition = "(:userId IS NULL OR diary.userId = :userId) " +
                "AND (:status IS NULL OR generation.status = :status) " +
                "AND (:from IS NULL OR generation.createdAt >= :from) " +
                "AND (:to IS NULL OR generation.createdAt < :to)";
        TypedQuery<AdminGenerationSnapshot> query = entityManager.createQuery("""
                SELECT new com.harudle.admin.repository.AdminGenerationSnapshot(
                    generation.id, user.id, user.name, user.primaryEmail,
                    generation.createdAt, generation.status, generation.completedAt, generation.errorCode)
                FROM DiaryGeneration generation
                JOIN Diary diary ON diary.id = generation.diaryId
                JOIN User user ON user.id = diary.userId
                WHERE """ + condition + " ORDER BY generation.createdAt DESC, generation.id DESC", AdminGenerationSnapshot.class);
        bind(query, userId, status, from, to);
        query.setFirstResult(page * size).setMaxResults(size);
        List<AdminGenerationSnapshot> content = query.getResultList();
        TypedQuery<Long> count = entityManager.createQuery("""
                SELECT COUNT(generation.id) FROM DiaryGeneration generation
                JOIN Diary diary ON diary.id = generation.diaryId
                WHERE """ + condition, Long.class);
        bind(count, userId, status, from, to);
        return new AdminGenerationPage(content, count.getSingleResult());
    }

    private void bind(TypedQuery<?> query, UUID userId, GenerationStatus status, Instant from, Instant to) {
        query.setParameter("userId", userId).setParameter("status", status)
                .setParameter("from", from).setParameter("to", to);
    }
}
