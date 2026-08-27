package com.harudle.admin.service;

import com.harudle.admin.service.exception.AdminGenerationUsageConflictException;
import com.harudle.admin.service.exception.AdminInactiveUserException;
import com.harudle.admin.service.exception.AdminUserNotFoundException;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.service.GenerationUsageService;
import com.harudle.guest.repository.GuestSessionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminGenerationUsageService {

    private final UserRepository userRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final GenerationUsageService generationUsageService;

    public AdminGenerationUsageService(
            UserRepository userRepository,
            GuestSessionRepository guestSessionRepository,
            GenerationUsageService generationUsageService
    ) {
        this.userRepository = userRepository;
        this.guestSessionRepository = guestSessionRepository;
        this.generationUsageService = generationUsageService;
    }

    @Transactional
    public GenerationUsage restore(UUID userId, int restoreCount) {
        findManageableUser(userId);
        return generationUsageService.restoreTodayUsage(userId, restoreCount)
                .orElseThrow(AdminGenerationUsageConflictException::new);
    }

    @Transactional
    public GenerationUsage reset(UUID userId) {
        User user = findManageableUser(userId);
        return generationUsageService.resetTodayUsage(userId, user.getDailyGenerationLimit());
    }

    @Transactional
    public void changeLimit(UUID userId, int limitCount) {
        User user = findManageableUser(userId);
        user.changeDailyGenerationLimit(limitCount);
        generationUsageService.updateTodayLimit(userId, limitCount);
    }

    private User findManageableUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(AdminUserNotFoundException::new);
        if (guestSessionRepository.existsByGuestUserId(userId)) {
            throw new AdminUserNotFoundException();
        }
        if (user.isDeleted()) {
            throw new AdminInactiveUserException();
        }
        return user;
    }
}
