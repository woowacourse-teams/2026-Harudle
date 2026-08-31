package com.harudle.admin.repository;

import com.harudle.admin.query.AdminGenerationSnapshot;
import com.harudle.admin.query.AdminUserDetailSnapshot;
import com.harudle.admin.query.AdminUserSnapshot;
import com.harudle.auth.domain.User;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AdminUserQueryRepository extends Repository<User, UUID> {

    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.harudle.admin.query.AdminUserDetailSnapshot(
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
            WHERE u.id = :userId
              AND NOT EXISTS (
                  SELECT guestSession.id
                  FROM GuestSession guestSession
                  WHERE guestSession.guestUserId = u.id
              )
            GROUP BY u.id, u.name, u.createdAt, u.deletedAt, u.dailyGenerationLimit,
                usage.usedCount, usage.limitCount
            """)
    Optional<AdminUserDetailSnapshot> findDetailSnapshot(
            @Param("userId") UUID userId,
            @Param("usageDate") LocalDate usageDate
    );

    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.harudle.admin.query.AdminGenerationSnapshot(
                generation.id,
                generation.createdAt,
                generation.status,
                generation.completedAt,
                generation.errorCode
            )
            FROM DiaryGeneration generation
            JOIN Diary diary ON diary.id = generation.diaryId
            WHERE diary.userId = :userId
            ORDER BY generation.createdAt DESC, generation.id DESC
            """)
    List<AdminGenerationSnapshot> findRecentGenerations(
            @Param("userId") UUID userId,
            Pageable pageable
    );

    @Transactional(readOnly = true)
    @Query(
            value = """
                    SELECT new com.harudle.admin.query.AdminUserSnapshot(
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
                    WHERE (
                        :query = ''
                        OR LOCATE(:query, LOWER(u.name)) > 0
                        OR (:exactUserId IS NOT NULL AND u.id = :exactUserId)
                    )
                      AND NOT EXISTS (
                          SELECT guestSession.id
                          FROM GuestSession guestSession
                          WHERE guestSession.guestUserId = u.id
                      )
                    GROUP BY u.id, u.name, u.createdAt, u.deletedAt, u.dailyGenerationLimit,
                        usage.usedCount, usage.limitCount
                    ORDER BY u.createdAt DESC, u.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(u)
                    FROM User u
                    WHERE (
                        :query = ''
                        OR LOCATE(:query, LOWER(u.name)) > 0
                        OR (:exactUserId IS NOT NULL AND u.id = :exactUserId)
                    )
                      AND NOT EXISTS (
                          SELECT guestSession.id
                          FROM GuestSession guestSession
                          WHERE guestSession.guestUserId = u.id
                      )
                    """
    )
    Page<AdminUserSnapshot> search(
            @Param("query") String normalizedQuery,
            @Param("exactUserId") UUID exactUserId,
            @Param("usageDate") LocalDate usageDate,
            Pageable pageable
    );
}
