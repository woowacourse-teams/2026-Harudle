package com.harudle.admin.service;

import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.exception.GenerationUnavailableException;
import com.harudle.generation.diary.service.port.DiaryImageGenerator;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.dto.DiaryImageGenerationRequest;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminImageRecoveryService {
    private final DiaryGenerationRepository generations;
    private final GenerationPromptRepository prompts;
    private final ObjectProvider<DiaryImageGenerator> generators;
    private final ObjectProvider<ImageStorage> storages;

    public AdminImageRecoveryService(DiaryGenerationRepository generations,
            GenerationPromptRepository prompts, ObjectProvider<DiaryImageGenerator> generators,
            ObjectProvider<ImageStorage> storages) {
        this.generations = generations;
        this.prompts = prompts;
        this.generators = generators;
        this.storages = storages;
    }

    public Result upload(String imageObjectKey, byte[] bytes) {
        if (imageObjectKey == null || imageObjectKey.isBlank()
                || imageObjectKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 S3 이미지 키가 필요합니다.");
        }
        var generation = generations.findFirstByImageObjectKey(imageObjectKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 S3 키의 생성 기록이 없습니다."));
        UUID generationId = generation.getId();
        String key = generation.getImageObjectKey();
        if (generation.getStatus() != GenerationStatus.SUCCEEDED || key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "기존 이미지 키가 있는 성공 기록만 복구할 수 있습니다.");
        }
        ImageStorage storage = storages.getIfAvailable();
        if (storage == null) {
            throw GenerationUnavailableException.adaptersNotConfigured();
        }
        if (storage.exists(key)) {
            return new Result(generationId, key, "ALREADY_EXISTS");
        }
        var image = RecoveryImageUpload.decode(bytes);
        if (!expectedContentType(key).equals(image.mediaType().toString())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "업로드 이미지 형식이 기존 키 확장자와 다릅니다. 필요한 형식: " + expectedContentType(key));
        }
        boolean restored = storage.restoreIfMissing(key, image);
        return new Result(generationId, key, restored ? "RESTORED" : "ALREADY_EXISTS");
    }

    public Result restore(UUID generationId) {
        var generation = generations.findById(generationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "생성 기록이 없습니다."));
        String key = generation.getImageObjectKey();
        if (generation.getStatus() != GenerationStatus.SUCCEEDED || generation.getStoryboard() == null
                || key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "스토리보드와 기존 이미지 키가 있는 성공 기록만 복구할 수 있습니다.");
        }
        ImageStorage storage = storages.getIfAvailable();
        DiaryImageGenerator generator = generators.getIfAvailable();
        if (storage == null || generator == null) {
            throw GenerationUnavailableException.adaptersNotConfigured();
        }
        if (storage.exists(key)) {
            return new Result(generationId, key, "ALREADY_EXISTS");
        }
        String expectedType = expectedContentType(key);
        var prompt = prompts.findFirstByOrderByIdDesc()
                .orElseThrow(GenerationUnavailableException::promptNotConfigured);
        var reference = storage.load(prompt.getImageAssetObjectKey());
        var image = generator.generate(new DiaryImageGenerationRequest(
                generation.getStoryboard(), prompt.getImageStylePromptText(), reference));
        if (!expectedType.equals(image.mediaType().getType() + "/" + image.mediaType().getSubtype())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "생성 이미지 형식이 기존 키 확장자와 달라 업로드하지 않았습니다. 필요한 형식: " + expectedType);
        }
        boolean restored = storage.restoreIfMissing(key, image);
        return new Result(generationId, key, restored ? "RESTORED" : "ALREADY_EXISTS");
    }

    private static String expectedContentType(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        throw new ResponseStatusException(HttpStatus.CONFLICT, "복구할 이미지 키의 확장자를 지원하지 않습니다.");
    }

    public record Result(UUID generationId, String imageObjectKey, String status) { }
}
