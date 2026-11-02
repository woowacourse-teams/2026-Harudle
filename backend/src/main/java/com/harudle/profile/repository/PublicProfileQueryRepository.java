package com.harudle.profile.repository;

import com.harudle.auth.domain.User;
import com.harudle.profile.query.PublicProfileSnapshot;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PublicProfileQueryRepository extends Repository<User, UUID> {

    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.harudle.profile.query.PublicProfileSnapshot(
                user.id,
                user.nickname,
                user.profileImageCode,
                user.profileImageObjectKey
            )
            FROM User user
            WHERE user.id IN :userIds
              AND user.deletedAt IS NULL
              AND user.nickname IS NOT NULL
              AND user.profileImageCode IS NOT NULL
            """)
    List<PublicProfileSnapshot> findAllByUserIds(@Param("userIds") Set<UUID> userIds);
}
