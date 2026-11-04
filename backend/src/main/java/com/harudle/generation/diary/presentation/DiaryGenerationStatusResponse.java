package com.harudle.generation.diary.presentation;

import com.harudle.common.error.ErrorType;
import com.harudle.common.error.GenerationErrorAction;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.service.dto.DiaryGenerationStatusResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

record DiaryGenerationStatusResponse(
        @Schema(description = "생성 작업 ID") UUID id,
        @Schema(description = "PENDING은 대기, PROCESSING은 처리 중, SUCCEEDED는 성공, FAILED는 실패")
        GenerationStatus status,
        @Schema(description = "성공한 경우의 일기 ID. 그 외에는 null") @Nullable UUID diaryId,
        @Schema(description = "실패한 경우의 오류 코드. 그 외에는 null") @Nullable GenerationErrorCode errorCode,
        @Schema(description = "실패 시 사용자에게 표시할 행동 안내 문구. 그 외에는 null") @Nullable String action
) {
    static DiaryGenerationStatusResponse from(DiaryGenerationStatusResult result) {
        return from(result, false);
    }

    static DiaryGenerationStatusResponse from(DiaryGenerationStatusResult result, boolean guest) {
        String action = result.status() == GenerationStatus.FAILED && result.errorCode() != null
                ? GenerationErrorAction.forFailure(ErrorType.from(result.errorCode()), guest) : null;
        return new DiaryGenerationStatusResponse(
                result.id(), result.status(), result.diaryId(), result.errorCode(), action
        );
    }
}
