package com.harudle.admin.service;

import com.harudle.admin.repository.AdminGenerationPage;
import com.harudle.admin.repository.AdminGenerationQueryRepository;
import com.harudle.generation.domain.GenerationStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdminGenerationQueryService {
    private final AdminGenerationQueryRepository repository;
    public AdminGenerationQueryService(AdminGenerationQueryRepository repository) { this.repository = repository; }
    public AdminGenerationPage search(UUID userId, GenerationStatus status, Instant from, Instant to, int page, int size) {
        return repository.search(userId, status, from, to, page, size);
    }
}
