package com.harudle.admin.repository;

import com.harudle.admin.query.AdminGenerationSnapshot;
import com.harudle.admin.query.AdminMemberStats;
import com.harudle.admin.query.AdminUserDetailSnapshot;
import com.harudle.admin.query.AdminUserSnapshot;
import com.harudle.admin.query.AdminUserSort;
import com.harudle.auth.domain.User;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
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
                u.nickname,
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
            GROUP BY u.id, u.name, u.nickname, u.createdAt, u.deletedAt, u.dailyGenerationLimit,
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
                        u.nickname,
                        u.createdAt,
                        u.deletedAt,
                        MAX(oa.lastLoginAt),
                        :usageDate,
                        COALESCE(usage.usedCount, 0),
                        COALESCE(usage.limitCount, u.dailyGenerationLimit),
                        (
                            SELECT COUNT(generation)
                            FROM DiaryGeneration generation
                            JOIN Diary diary ON diary.id = generation.diaryId
                            WHERE diary.userId = u.id
                              AND generation.status = com.harudle.generation.diary.domain.GenerationStatus.SUCCEEDED
                        )
                    )
                    FROM User u
                    LEFT JOIN OAuthAccount oa ON oa.user = u
                    LEFT JOIN DailyGenerationUsage usage
                        ON usage.id.userId = u.id AND usage.id.usageDate = :usageDate
                    WHERE (
                        :query = ''
                        OR LOCATE(:query, LOWER(u.name)) > 0
                        OR LOCATE(:query, LOWER(u.nickname)) > 0
                        OR (:exactUserId IS NOT NULL AND u.id = :exactUserId)
                    )
                      AND NOT EXISTS (
                          SELECT guestSession.id
                          FROM GuestSession guestSession
                          WHERE guestSession.guestUserId = u.id
                      )
                    GROUP BY u.id, u.name, u.nickname, u.createdAt, u.deletedAt, u.dailyGenerationLimit,
                        usage.usedCount, usage.limitCount
                    """,
            countQuery = """
                    SELECT COUNT(u)
                    FROM User u
                    WHERE (
                        :query = ''
                        OR LOCATE(:query, LOWER(u.name)) > 0
                        OR LOCATE(:query, LOWER(u.nickname)) > 0
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

    default Page<AdminUserSnapshot> search(
            String normalizedQuery,
            UUID exactUserId,
            LocalDate usageDate,
            AdminUserSort sort,
            Sort.Direction direction,
            int page,
            int size
    ) {
        return search(
                normalizedQuery,
                exactUserId,
                usageDate,
                PageRequest.of(page, size, orderBy(sort, direction))
        );
    }

    private static Sort orderBy(AdminUserSort sort, Sort.Direction direction) {
        Sort primary = switch (sort) {
            case NAME -> Sort.by(direction, "name");
            case CREATED_AT -> Sort.by(direction, "createdAt");
            case REMAINING_COUNT -> JpaSort.unsafe(
                    direction,
                    "(COALESCE(usage.limitCount, u.dailyGenerationLimit) - COALESCE(usage.usedCount, 0))"
            );
        };
        return primary.and(Sort.by(direction, "id"));
    }

    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.harudle.admin.query.AdminMemberStats(
                COUNT(u),
                COALESCE(SUM(CASE
                    WHEN u.createdAt >= :todayStart AND u.createdAt < :tomorrowStart THEN 1
                    ELSE 0
                END), 0)
            )
            FROM User u
            WHERE u.deletedAt IS NULL
              AND NOT EXISTS (
                  SELECT guestSession.id
                  FROM GuestSession guestSession
                  WHERE guestSession.guestUserId = u.id
              )
            """)
    AdminMemberStats countMembers(
            @Param("todayStart") Instant todayStart,
            @Param("tomorrowStart") Instant tomorrowStart
    );
}
