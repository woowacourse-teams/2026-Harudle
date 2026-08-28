package com.harudle.admin.service;

import com.harudle.admin.domain.AdminGenerationUsageRestore;
import com.harudle.admin.repository.AdminGenerationUsageRestoreRepository;
import com.harudle.admin.service.exception.AdminGenerationUsageConflictException;
import com.harudle.admin.service.exception.AdminInactiveUserException;
import com.harudle.admin.service.exception.AdminUserNotFoundException;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.service.GenerationUsageService;
import com.harudle.generation.service.exception.IdempotencyKeyConflictException;
import com.harudle.guest.repository.GuestSessionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminGenerationUsageService {

    private final UserRepository userRepository;
    private final GuestSessionRepository guestSessionRepository;
    private final GenerationUsageService generationUsageService;
    private final AdminGenerationUsageRestoreRepository restoreRepository;

    public AdminGenerationUsageService(
            UserRepository userRepository,
            GuestSessionRepository guestSessionRepository,
            GenerationUsageService generationUsageService,
            AdminGenerationUsageRestoreRepository restoreRepository
    ) {
        this.userRepository = userRepository;
        this.guestSessionRepository = guestSessionRepository;
        this.generationUsageService = generationUsageService;
        this.restoreRepository = restoreRepository;
    }

    @Transactional(noRollbackFor = AdminGenerationUsageConflictException.class)
    public GenerationUsage restore(UUID userId, int restoreCount, UUID idempotencyKey) {
        validateRestoreRequest(restoreCount, idempotencyKey);
        findManageableUserForUpdate(userId);
        restoreRepository.claim(idempotencyKey, userId, restoreCount);

        AdminGenerationUsageRestore restore = restoreRepository
                .findByIdempotencyKeyForUpdate(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("사용량 복구 멱등성 기록을 찾을 수 없습니다."));
        if (!restore.matches(userId, restoreCount)) {
            throw new IdempotencyKeyConflictException();
        }
        if (restore.isSucceeded()) {
            return restore.toGenerationUsage();
        }
        if (restore.isConflict()) {
            throw new AdminGenerationUsageConflictException();
        }

        GenerationUsage usage = generationUsageService.restoreTodayUsage(userId, restoreCount)
                .orElse(null);
        if (usage == null) {
            restore.markConflict();
            throw new AdminGenerationUsageConflictException();
        }
        restore.complete(usage);
        return usage;
    }

    @Transactional
    public GenerationUsage reset(UUID userId) {
        User user = findManageableUserForUpdate(userId);
        return generationUsageService.resetTodayUsage(userId, user.getDailyGenerationLimit());
    }

    @Transactional
    public void changeLimit(UUID userId, int limitCount) {
        User user = findManageableUserForUpdate(userId);
        user.changeDailyGenerationLimit(limitCount);
        generationUsageService.updateTodayLimit(userId, limitCount);
    }

    private User findManageableUserForUpdate(UUID userId) {
        return validateManageableUser(userRepository.findByIdForUpdate(userId));
    }

    private User validateManageableUser(Optional<User> userOptional) {
        User user = userOptional
                .orElseThrow(AdminUserNotFoundException::new);
        if (guestSessionRepository.existsByGuestUserId(user.getId())) {
            throw new AdminUserNotFoundException();
        }
        if (user.isDeleted()) {
            throw new AdminInactiveUserException();
        }
        return user;
    }

    private static void validateRestoreRequest(int restoreCount, UUID idempotencyKey) {
        if (restoreCount < 1) {
            throw new IllegalArgumentException("복구 횟수는 1 이상이어야 합니다.");
        }
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("멱등성 키는 필수입니다.");
        }
    }
}
