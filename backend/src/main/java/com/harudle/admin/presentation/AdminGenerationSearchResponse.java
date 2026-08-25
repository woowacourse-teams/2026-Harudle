package com.harudle.admin.presentation;

import com.harudle.admin.repository.AdminGenerationPage;
import com.harudle.generation.domain.GenerationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record AdminGenerationSearchResponse(List<GenerationResponse> content, int page, int size,
                                     long totalElements, int totalPages, boolean hasNext) {
    static AdminGenerationSearchResponse from(AdminGenerationPage result, int page, int size) {
        int totalPages = (int) Math.ceil((double) result.totalElements() / size);
        return new AdminGenerationSearchResponse(result.content().stream().map(GenerationResponse::from).toList(),
                page, size, result.totalElements(), totalPages, page + 1 < totalPages);
    }
    record GenerationResponse(UUID id, UserResponse user, Instant requestedAt, GenerationStatus status,
                               Instant completedAt, String errorCode) {
        static GenerationResponse from(com.harudle.admin.repository.AdminGenerationSnapshot value) {
            return new GenerationResponse(value.id(), new UserResponse(value.userId(), value.userName(), value.email()),
                    value.requestedAt(), value.status(), value.completedAt(), value.errorCode());
        }
    }
    record UserResponse(UUID id, String name, String email) {}
}
