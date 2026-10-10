package com.harudle.auth.infrastructure.nickname;

import com.harudle.auth.domain.nickname.NicknameCandidate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.MANDATORY)
public interface NicknameCandidateJpaRepository extends Repository<NicknameCandidate, String> {

    String AVAILABLE_CONDITION = """
            c.assigned_user_id IS NULL
            AND NOT EXISTS (
                SELECT 1 FROM users u
                WHERE u.nickname = c.nickname AND u.deleted_at IS NULL
            )
            """;

    @Query(value = """
            SELECT c.nickname FROM nickname_candidates c
            WHERE
            """ + AVAILABLE_CONDITION + """
            ORDER BY c.allocation_order
            LIMIT 1
            FOR UPDATE OF c SKIP LOCKED
            """, nativeQuery = true)
    Optional<String> lockNextAvailableNickname();

    @Query(value = "SELECT EXISTS (SELECT 1 FROM nickname_candidates c WHERE "
            + AVAILABLE_CONDITION + ")", nativeQuery = true)
    boolean existsAvailableNickname();

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE nickname_candidates c SET assigned_user_id = :userId
            WHERE c.nickname = :nickname
              AND (c.assigned_user_id IS NULL OR c.assigned_user_id = :userId)
              AND EXISTS (
                  SELECT 1 FROM users u
                  WHERE u.id = :userId AND u.nickname = c.nickname AND u.deleted_at IS NULL
              )
            """, nativeQuery = true)
    int assignToUser(@Param("nickname") String nickname, @Param("userId") UUID userId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE nickname_candidates c SET assigned_user_id = NULL
            WHERE c.nickname = :nickname AND c.assigned_user_id = :userId
              AND NOT EXISTS (
                  SELECT 1 FROM users u
                  WHERE u.nickname = c.nickname AND u.deleted_at IS NULL
              )
            """, nativeQuery = true)
    int releaseFromUser(@Param("nickname") String nickname, @Param("userId") UUID userId);
}
