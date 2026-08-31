package com.harudle.admin.presentation.dto;

import com.harudle.admin.query.AdminGenerationHistoryPage;
import com.harudle.admin.query.AdminGenerationHistorySnapshot;
import com.harudle.generation.domain.GenerationErrorCode;
import com.harudle.generation.domain.GenerationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminGenerationHistoryResponse(
        List<GenerationResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    public static AdminGenerationHistoryResponse from(
            AdminGenerationHistoryPage result,
            int page,
            int size
    ) {
        int totalPages = (int) Math.ceil((double) result.totalElements() / size);
        return new AdminGenerationHistoryResponse(
                result.content().stream()
                        .map(GenerationResponse::from)
                        .toList(),
                page,
                size,
                result.totalElements(),
                totalPages,
                page + 1 < totalPages
        );
    }

    public record GenerationResponse(
            UUID id,
            UserResponse user,
            Instant requestedAt,
            GenerationStatus status,
            Instant completedAt,
            GenerationErrorCode errorCode
    ) {

        private static GenerationResponse from(AdminGenerationHistorySnapshot generation) {
            return new GenerationResponse(
                    generation.id(),
                    new UserResponse(generation.userId(), generation.userName()),
                    generation.requestedAt(),
                    generation.status(),
                    generation.completedAt(),
                    generation.errorCode()
            );
        }
    }

    public record UserResponse(
            UUID id,
            String name
    ) {
    }
}
