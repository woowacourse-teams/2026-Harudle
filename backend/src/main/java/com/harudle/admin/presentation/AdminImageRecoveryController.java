package com.harudle.admin.presentation;

import com.harudle.admin.service.AdminImageRecoveryService;
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
    AdminImageRecoveryService.Result upload(@RequestParam("imageObjectKey") String imageObjectKey,
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
    AdminImageRecoveryService.Result restore(@PathVariable UUID generationId) {
        return recoveryService.restore(generationId);
    }
}
