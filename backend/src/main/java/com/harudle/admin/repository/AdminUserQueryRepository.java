package com.harudle.admin.repository;

import com.harudle.admin.query.AdminUserDetail;
import com.harudle.admin.query.AdminUserPage;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserQueryRepository {

    Optional<AdminUserDetail> findDetail(UUID userId, LocalDate usageDate);

    AdminUserPage search(
            String normalizedQuery,
            Optional<UUID> exactUserId,
            LocalDate usageDate,
            int page,
            int size
    );
}
