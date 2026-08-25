package com.harudle.admin.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserQueryRepository {

    AdminUserDetail findDetail(UUID userId, LocalDate usageDate);

    AdminUserPage search(
            String normalizedQuery,
            Optional<UUID> exactUserId,
            LocalDate usageDate,
            int page,
            int size
    );
}
