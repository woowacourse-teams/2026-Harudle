package com.harudle.admin.service;

import com.harudle.admin.repository.AdminUserPage;
import com.harudle.admin.repository.AdminUserQueryRepository;
import com.harudle.common.validation.CanonicalUuidParser;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AdminUserQueryService {

    private final AdminUserQueryRepository adminUserQueryRepository;
    private final Clock clock;

    public AdminUserQueryService(
            AdminUserQueryRepository adminUserQueryRepository,
            @Qualifier("serviceClock") Clock clock
    ) {
        this.adminUserQueryRepository = adminUserQueryRepository;
        this.clock = clock;
    }

    public AdminUserPage search(String query, int page, int size) {
        String normalizedQuery = Objects.requireNonNullElse(query, "")
                .strip()
                .toLowerCase(Locale.ROOT);
        return adminUserQueryRepository.search(
                normalizedQuery,
                CanonicalUuidParser.parse(normalizedQuery),
                LocalDate.now(clock),
                page,
                size
        );
    }

    public com.harudle.admin.repository.AdminUserDetail findDetail(UUID userId) {
        return adminUserQueryRepository.findDetail(userId, LocalDate.now(clock));
    }
}
