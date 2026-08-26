package com.harudle.admin.service;

import com.harudle.admin.service.exception.AdminGenerationUsageConflictException;
import com.harudle.admin.service.exception.AdminInactiveUserException;
import com.harudle.admin.service.exception.AdminUserNotFoundException;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.service.GenerationUsageService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminGenerationUsageService {

    private final UserRepository userRepository;
    private final GenerationUsageService generationUsageService;

    public AdminGenerationUsageService(
            UserRepository userRepository,
            GenerationUsageService generationUsageService
    ) {
        this.userRepository = userRepository;
        this.generationUsageService = generationUsageService;
    }

    @Transactional
    public GenerationUsage restore(UUID userId, int restoreCount) {
        User user = userRepository.findById(userId)
                .orElseThrow(AdminUserNotFoundException::new);
        if (user.isDeleted()) {
            throw new AdminInactiveUserException();
        }
        return generationUsageService.restoreTodayUsage(userId, restoreCount)
                .orElseThrow(AdminGenerationUsageConflictException::new);
    }
}
