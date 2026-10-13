package com.harudle.admin.presentation;

import com.harudle.admin.service.AdminImageRecoveryService;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ApiFrameworkError;
import com.harudle.common.error.ErrorType;
import java.util.UUID;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/generations")
class AdminImageRecoveryController {
    private final AdminImageRecoveryService recoveryService;

    AdminImageRecoveryController(AdminImageRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @PostMapping(value = "/restore-image/upload", consumes = "multipart/form-data")
    @ApiErrorResponses(value = {
            ErrorType.VALIDATION_ERROR,
            ErrorType.UNAUTHORIZED,
            ErrorType.FORBIDDEN,
            ErrorType.GENERATION_UNAVAILABLE,
            ErrorType.IMAGE_STORAGE_ERROR
    }, framework = {
            @ApiFrameworkError(status = 400, name = "빈 이미지", detail = "이미지 파일이 비어 있습니다."),
            @ApiFrameworkError(status = 400, name = "잘못된 이미지 키", detail = "유효한 S3 이미지 키가 필요합니다."),
            @ApiFrameworkError(status = 400, name = "이미지 픽셀 초과", detail = "이미지는 2500만 픽셀 이하여야 합니다."),
            @ApiFrameworkError(status = 400, name = "이미지 읽기 실패", detail = "이미지 파일을 읽을 수 없습니다."),
            @ApiFrameworkError(status = 409, name = "PNG 이미지 키와 업로드 형식 불일치",
                    detail = "업로드 이미지 형식이 기존 키 확장자와 다릅니다. 필요한 형식: image/png"),
            @ApiFrameworkError(status = 409, name = "미지원 이미지 키 확장자",
                    detail = "복구할 이미지 키의 확장자를 지원하지 않습니다."),
            @ApiFrameworkError(status = 413, name = "이미지 크기 초과", detail = "이미지는 20MiB 이하여야 합니다."),
            @ApiFrameworkError(status = 415, name = "미지원 이미지 파일 형식",
                    detail = "복구 업로드는 PNG 또는 JPEG 파일만 지원합니다."),
            @ApiFrameworkError(status = 503, name = "복구 작업 대기 중단",
                    detail = "복구 작업 대기가 중단되었습니다.")
    })
    AdminImageRecoveryService.UploadResult upload(@RequestParam("imageObjectKey") String imageObjectKey,
            @RequestPart("image") MultipartFile image) throws IOException {
        if (image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지 파일이 비어 있습니다.");
        }
        if (image.getSize() > 20L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "이미지는 20MiB 이하여야 합니다.");
        }
        return recoveryService.upload(imageObjectKey, image.getBytes());
    }

    @PostMapping("/{generationId}/restore-image")
    @ApiErrorResponses(value = {
            ErrorType.VALIDATION_ERROR,
            ErrorType.UNAUTHORIZED,
            ErrorType.FORBIDDEN,
            ErrorType.AI_PROVIDER_ERROR,
            ErrorType.GENERATION_UNAVAILABLE,
            ErrorType.IMAGE_STORAGE_ERROR,
            ErrorType.AI_PROVIDER_TIMEOUT
    }, framework = {
            @ApiFrameworkError(status = 404, name = "생성 기록 없음", detail = "생성 기록이 없습니다."),
            @ApiFrameworkError(status = 409, name = "복구 대상 상태 불일치",
                    detail = "스토리보드와 기존 이미지 키가 있는 성공 기록만 복구할 수 있습니다."),
            @ApiFrameworkError(status = 409, name = "미지원 이미지 키 확장자",
                    detail = "복구할 이미지 키의 확장자를 지원하지 않습니다."),
            @ApiFrameworkError(status = 409, name = "PNG 이미지 키와 생성 형식 불일치",
                    detail = "생성 이미지 형식이 기존 키 확장자와 달라 업로드하지 않았습니다. 필요한 형식: image/png"),
            @ApiFrameworkError(status = 503, name = "복구 작업 대기 중단",
                    detail = "복구 작업 대기가 중단되었습니다.")
    })
    AdminImageRecoveryService.Result restore(@PathVariable UUID generationId) {
        return recoveryService.restore(generationId);
    }
}
