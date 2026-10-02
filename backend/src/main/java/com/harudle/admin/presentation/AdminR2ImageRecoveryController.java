package com.harudle.admin.presentation;

import com.harudle.admin.service.AdminR2ImageRecoveryService;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ApiFrameworkError;
import com.harudle.common.error.ErrorType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/generations/restore-image/r2")
class AdminR2ImageRecoveryController {
    private final AdminR2ImageRecoveryService service;

    AdminR2ImageRecoveryController(AdminR2ImageRecoveryService service) {
        this.service = service;
    }

    @PostMapping
    @ApiErrorResponses(value = {ErrorType.VALIDATION_ERROR, ErrorType.UNAUTHORIZED, ErrorType.FORBIDDEN,
            ErrorType.INVALID_CSRF_TOKEN}, framework = {
            @ApiFrameworkError(status = 400, name = "잘못된 복구 목록", detail = "중복 없는 생성 기록 UUID를 1~100개 지정하세요."),
            @ApiFrameworkError(status = 409, name = "복구 환경 불일치", detail = "요청 환경과 서버의 복구 환경이 다릅니다."),
            @ApiFrameworkError(status = 503, name = "복구 설정 없음", detail = "S3와 R2 복구 설정이 필요합니다."),
            @ApiFrameworkError(status = 503, name = "복구 작업 대기 중단", detail = "복구 작업 대기가 중단되었습니다.")
    })
    AdminR2ImageRecoveryService.BatchResult recover(@Valid @RequestBody RecoveryRequest request) {
        return service.recover(request.environment(), request.generationIds(), request.dryRun());
    }

    record RecoveryRequest(@NotBlank @Pattern(regexp = "dev|prod") String environment,
            @NotEmpty @Size(max = 100) List<@NotNull UUID> generationIds, @NotNull Boolean dryRun) {
    }
}
