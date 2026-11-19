package com.harudle.admin.service;

import com.harudle.admin.query.AdminMemberStats;
import com.harudle.admin.query.AdminUserDetail;
import com.harudle.admin.query.AdminUserDetailSnapshot;
import com.harudle.admin.query.AdminUserPage;
import com.harudle.admin.query.AdminUserSort;
import com.harudle.admin.repository.AdminUserQueryRepository;
import com.harudle.admin.service.exception.AdminUserNotFoundException;
import com.harudle.common.validation.CanonicalUuidParser;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    public AdminUserPage search(
            String query,
            AdminUserSort sort,
            Sort.Direction direction,
            int page,
            int size
    ) {
        String normalizedQuery = Objects.requireNonNullElse(query, "")
                .strip()
                .toLowerCase(Locale.ROOT);
        var result = adminUserQueryRepository.search(
                normalizedQuery,
                CanonicalUuidParser.parse(normalizedQuery).orElse(null),
                LocalDate.now(clock),
                sort,
                direction,
                page,
                size
        );
        return new AdminUserPage(result.getContent(), result.getTotalElements());
    }

    public AdminMemberStats findMemberStats() {
        LocalDate today = LocalDate.now(clock);
        return adminUserQueryRepository.countMembers(
                today.atStartOfDay(clock.getZone()).toInstant(),
                today.plusDays(1).atStartOfDay(clock.getZone()).toInstant()
        );
    }

    public AdminUserDetail findDetail(UUID userId) {
        AdminUserDetailSnapshot user = adminUserQueryRepository
                .findDetailSnapshot(userId, LocalDate.now(clock))
                .orElseThrow(AdminUserNotFoundException::new);
        var recentGenerations = adminUserQueryRepository
                .findRecentGenerations(userId, PageRequest.of(0, 5))
                .stream()
                .map(generation -> new AdminUserDetail.RecentGeneration(
                        generation.id(),
                        generation.requestedAt(),
                        generation.status(),
                        generation.completedAt(),
                        generation.errorCode()
                ))
                .toList();
        return new AdminUserDetail(
                user.id(),
                user.name(),
                user.nickname(),
                user.createdAt(),
                user.deletedAt(),
                user.lastLoginAt(),
                user.usageDate(),
                user.usedCount(),
                user.limitCount(),
                recentGenerations
        );
    }
}
